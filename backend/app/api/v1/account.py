import logging
import uuid
from html import escape
from urllib.parse import parse_qs, quote

from email_validator import EmailNotValidError, validate_email
from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from app.core import passwords, reset
from app.core.errors import APIError
from app.core.rate_limit import limit_account, rate_limited
from app.core.security import create_user_token, current_user, revoked_key
from app.schemas.account import AccountAuthResponse, LoginRequest, RegisterRequest, UserOut
from app.services import mailer
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


@router.get("/subscription")
async def subscription(request: Request, user_id: uuid.UUID = Depends(current_user)) -> dict:
    """For the app's Settings: is this account paid up, until when, and where to pay (the website)."""
    s = request.app.state.settings
    user = await request.app.state.users.get(user_id)
    if user is None:
        raise APIError(401, "unauthorized", "Unknown account.", "Please sign in again.")
    ent = await request.app.state.entitlements.for_email(user.email, user.created_at)
    return {
        "active": ent.active,
        "source": ent.source,
        "until": ent.until.isoformat() if ent.until else None,
        "price_ngn": s.price_ngn,
        "enforced": s.require_subscription,
        "pay_url": f"{s.site_url.rstrip('/')}/pay?email={quote(user.email)}&from=app",
    }


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
    await mailer.send_welcome(request.app.state.settings, user.email)  # never raises
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


# --- forgotten password ------------------------------------------------------------------------------------
# An emailed link opens a small web page (the app cannot open links itself). The link is signed, expires in an
# hour and stops working the moment the password changes. "Send me a link" always answers the same way.

_BAD_LINK = "This reset link is wrong, already used, or too old. Ask for a new one from the app."


class ResetRequest(BaseModel):
    email: str


class ResetConfirm(BaseModel):
    token: str
    password: str


def _check_new_password(password: str) -> None:
    if not (passwords.MIN_LENGTH <= len(password) <= passwords.MAX_LENGTH):
        raise _invalid(
            f"Password must be {passwords.MIN_LENGTH} to {passwords.MAX_LENGTH} characters.",
            f"Please choose a password with at least {passwords.MIN_LENGTH} characters.",
        )


async def _user_for_reset_token(request: Request, token: str):
    """(user, current hash) when the link is genuine and unused, else None."""
    parsed = reset.read_reset_token(token, request.app.state.settings)
    if parsed is None:
        return None
    user_id, fingerprint = parsed
    found = await request.app.state.users.credentials(user_id)
    if found is None or not reset.matches(fingerprint, found[1]):
        return None
    return found


async def _change_password(request: Request, token: str, password: str) -> None:
    found = await _user_for_reset_token(request, token)
    if found is None:
        raise APIError(400, "invalid_link", _BAD_LINK, "That reset link has expired. Please ask for a new one.")
    _check_new_password(password)
    new_hash = await run_in_threadpool(passwords.hash_password, password)
    await request.app.state.users.set_password(found[0].id, new_hash)
    log.info("password reset")


@router.post("/password-reset")
async def password_reset_request(body: ResetRequest, request: Request) -> dict:
    email = _clean_email(body.email)
    found = await request.app.state.users.find_by_email(email)
    if found is not None:
        settings = request.app.state.settings
        token = reset.create_reset_token(found[0].id, found[1], settings)
        link = f"{settings.public_base_url.rstrip('/')}/v1/auth/password-reset/page?token={quote(token)}"
        await mailer.send_password_reset(settings, found[0].email, link)
    return {"detail": "If that email has an account, we sent a link to reset the password."}


@router.post("/password-reset/confirm")
async def password_reset_confirm(body: ResetConfirm, request: Request) -> dict:
    await _change_password(request, body.token, body.password)
    return {"detail": "Password changed. You can sign in now."}


def _reset_page(inner: str, status_code: int = 200) -> HTMLResponse:
    html = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Choose a new password | VisionMirror</title>
<style>body{{font:18px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f5f7fa;color:#0b192c}}
main{{max-width:440px;margin:0 auto;padding:32px 16px}}.card{{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:24px}}
label{{display:block;margin:14px 0 4px;font-size:16px}}input{{width:100%;box-sizing:border-box;padding:14px;border:1px solid #cbd5e1;border-radius:10px;font-size:18px}}
button{{margin-top:18px;width:100%;padding:15px;border:0;border-radius:10px;background:#0b192c;color:#fff;font-size:18px}}.err{{color:#b91c1c}}.ok{{color:#166534}}</style>
</head><body><main><h1>VisionMirror</h1><div class="card">{inner}</div></main></body></html>"""
    return HTMLResponse(html, status_code=status_code, headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"})


def _reset_form(token: str, error: str = "") -> str:
    err = f'<p class="err" role="alert">{escape(error)}</p>' if error else ""
    return (
        f'<form method="post" action="/v1/auth/password-reset/page"><input type="hidden" name="token" value="{escape(token, quote=True)}">'
        '<label for="password">New password</label><input id="password" name="password" type="password" autocomplete="new-password" required minlength="8">'
        '<label for="confirm">Repeat the new password</label><input id="confirm" name="confirm" type="password" autocomplete="new-password" required minlength="8">'
        f'{err}<button type="submit">Save new password</button></form>'
    )


@router.get("/password-reset/page", response_class=HTMLResponse)
async def password_reset_page(request: Request, token: str = "") -> HTMLResponse:
    if await _user_for_reset_token(request, token) is None:
        return _reset_page(f"<p>{escape(_BAD_LINK)}</p>", 400)
    return _reset_page(_reset_form(token))


@router.post("/password-reset/page", response_class=HTMLResponse)
async def password_reset_page_submit(request: Request) -> HTMLResponse:
    fields = {k: v[0] for k, v in parse_qs((await request.body()).decode("utf-8", "ignore")).items()}
    token, password, confirm = fields.get("token", ""), fields.get("password", ""), fields.get("confirm", "")
    if await _user_for_reset_token(request, token) is None:
        return _reset_page(f"<p>{escape(_BAD_LINK)}</p>", 400)
    if password != confirm:
        return _reset_page(_reset_form(token, "The two passwords do not match."), 400)
    try:
        await _change_password(request, token, password)
    except APIError as exc:
        return _reset_page(_reset_form(token, exc.message), 400)
    return _reset_page('<p class="ok">Your password was changed. Open the VisionMirror app and sign in with the new one.</p>')
