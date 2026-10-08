import logging

from email_validator import EmailNotValidError, validate_email
from fastapi import APIRouter, Depends, Request, Response
from starlette.concurrency import run_in_threadpool

from app.core import passwords
from app.core.errors import APIError
from app.core.rate_limit import limit_account, rate_limited
from app.core.security import create_user_token, current_user, revoked_key
from app.schemas.account import AccountAuthResponse, LoginRequest, RegisterRequest, UserOut
from app.services.user_store import EmailTaken, User

log = logging.getLogger("visionmirror.account")

router = APIRouter(prefix="/auth", tags=["account"], dependencies=[Depends(limit_account)])


def _invalid(message: str, spoken: str) -> APIError:
    return APIError(400, "invalid_input", message, spoken)


def _clean_email(raw: str) -> str:
    try:
        return validate_email(raw.strip(), check_deliverability=False).normalized.lower()
    except EmailNotValidError:
        raise _invalid("Invalid email address.", "That email address doesn't look right. Please check it and try again.")


def _out(user: User) -> UserOut:
    return UserOut(id=user.id, email=user.email, display_name=user.display_name)


def _token_response(user: User, request: Request) -> AccountAuthResponse:
    token, expires_in = create_user_token(user.id, request.app.state.settings)
    return AccountAuthResponse(access_token=token, expires_in=expires_in, user=_out(user))


@router.post("/register", response_model=AccountAuthResponse, status_code=201)
async def register(body: RegisterRequest, request: Request) -> AccountAuthResponse:
    email = _clean_email(body.email)
    if not (passwords.MIN_LENGTH <= len(body.password) <= passwords.MAX_LENGTH):
        raise _invalid(
            f"Password must be {passwords.MIN_LENGTH} to {passwords.MAX_LENGTH} characters.",
            f"Please choose a password with at least {passwords.MIN_LENGTH} characters.",
        )
    if body.password.lower() == email:
        raise _invalid("Password must not be the email.", "Your password can't be the same as your email. Please choose another.")
    name = (body.display_name or "").strip()[:60] or None

    password_hash = await run_in_threadpool(passwords.hash_password, body.password)
    try:
        user = await request.app.state.users.create(email, password_hash, name)
    except EmailTaken:
        raise APIError(
            409,
            "email_taken",
            "An account with this email already exists.",
            "That email already has an account. Try signing in instead.",
        )
    log.info("account created")
    return _token_response(user, request)


@router.post("/login", response_model=AccountAuthResponse)
async def login(body: LoginRequest, request: Request) -> AccountAuthResponse:
    email = _clean_email(body.email)
    throttle = request.app.state.login_throttle
    if (wait := await throttle.locked_for(email)) is not None:
        raise rate_limited(wait, " signing in")
    found = await request.app.state.users.find_by_email(email)
    stored_hash = found[1] if found else None
    ok = await run_in_threadpool(passwords.verify_password, body.password, stored_hash)
    if not ok or found is None:
        await throttle.failed(email)
        raise APIError(
            401,
            "invalid_credentials",
            "Email or password is incorrect.",
            "That email or password isn't right. Please try again.",
        )
    await throttle.succeeded(email)
    return _token_response(found[0], request)


@router.get("/me", response_model=UserOut)
async def me(request: Request, user_id=Depends(current_user)) -> UserOut:
    user = await request.app.state.users.get(user_id)
    if user is None:
        raise APIError(401, "unauthorized", "Account no longer exists.", "I couldn't sign you in. Please sign in again.")
    return _out(user)


@router.delete("/me", status_code=204)
async def delete_me(request: Request, user_id=Depends(current_user)) -> Response:
    """Permanently deletes the account. Its tokens stop working immediately."""
    await request.app.state.users.delete(user_id)
    state = request.app.state
    await state.redis.set(revoked_key(str(user_id)), "1", ex=state.settings.user_token_ttl_seconds)
    log.info("account deleted")
    return Response(status_code=204)
