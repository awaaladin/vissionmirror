import hashlib
import time
import uuid

import jwt
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import Settings
from app.core.errors import APIError

ISSUER = "visionmirror"
ANON = "anon"
USER = "user"
_bearer = HTTPBearer(auto_error=False)


def _encode(subject: str, kind: str, ttl: int, settings: Settings) -> tuple[str, int]:
    now = int(time.time())
    payload = {"iss": ISSUER, "sub": subject, "typ": kind, "iat": now, "exp": now + ttl}
    return jwt.encode(payload, settings.secret_key, algorithm="HS256"), ttl


def create_token(device_id: uuid.UUID, settings: Settings) -> tuple[str, int]:
    """Guest token, bound to a random device ID the app generated."""
    return _encode(str(device_id), ANON, settings.token_ttl_seconds, settings)


def create_user_token(user_id: uuid.UUID, settings: Settings) -> tuple[str, int]:
    """Signed-in token, bound to the account. Lives longer: signing in again is hard without sight."""
    return _encode(str(user_id), USER, settings.user_token_ttl_seconds, settings)


def decode_claims(token: str, settings: Settings) -> dict:
    """Return the verified claims, or raise jwt.PyJWTError."""
    return jwt.decode(
        token,
        settings.secret_key,
        algorithms=["HS256"],
        issuer=ISSUER,
        options={"require": ["exp", "sub", "iss"]},
    )


def decode_token(token: str, settings: Settings) -> str:
    """Return the subject (device ID or user ID) the token is bound to, or raise jwt.PyJWTError."""
    return decode_claims(token, settings)["sub"]


def revoked_key(user_id: str) -> str:
    return f"revoked_user:{user_id}"


def _unauthorized(message: str) -> APIError:
    return APIError(
        401,
        "unauthorized",
        message,
        "I couldn't sign you in. Please close the app and open it again.",
        headers={"WWW-Authenticate": "Bearer"},
    )


async def _claims(request: Request, creds: HTTPAuthorizationCredentials | None) -> dict:
    if creds is None:
        raise _unauthorized("Missing bearer token.")
    try:
        claims = decode_claims(creds.credentials, request.app.state.settings)
    except jwt.PyJWTError:
        raise _unauthorized("Invalid or expired token.")
    if claims.get("typ") == USER and await request.app.state.redis.exists(revoked_key(claims["sub"])):
        raise _unauthorized("This account was deleted.")  # a deleted account's token must stop working at once
    request.state.device_hash = hashlib.sha256(claims["sub"].encode()).hexdigest()[:8]  # for logs only
    return claims


async def current_device(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> str:
    """The subject the request acts as: a guest's device ID or a signed-in user's ID."""
    return (await _claims(request, creds))["sub"]


async def current_user(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> uuid.UUID:
    """Only for signed-in accounts; a guest token is not enough."""
    claims = await _claims(request, creds)
    if claims.get("typ") != USER:
        raise APIError(
            401,
            "sign_in_required",
            "This needs a signed-in account.",
            "Please sign in to do that.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return uuid.UUID(claims["sub"])
