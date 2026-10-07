import hashlib
import time
import uuid

import jwt
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import Settings
from app.core.errors import APIError

ISSUER = "visionmirror"
_bearer = HTTPBearer(auto_error=False)


def create_token(device_id: uuid.UUID, settings: Settings) -> tuple[str, int]:
    now = int(time.time())
    payload = {
        "iss": ISSUER,
        "sub": str(device_id),
        "iat": now,
        "exp": now + settings.token_ttl_seconds,
    }
    return jwt.encode(payload, settings.secret_key, algorithm="HS256"), settings.token_ttl_seconds


def decode_token(token: str, settings: Settings) -> str:
    """Return the device id the token is bound to, or raise jwt.PyJWTError."""
    payload = jwt.decode(
        token,
        settings.secret_key,
        algorithms=["HS256"],
        issuer=ISSUER,
        options={"require": ["exp", "sub", "iss"]},
    )
    return payload["sub"]


def _unauthorized(message: str) -> APIError:
    return APIError(
        401,
        "unauthorized",
        message,
        "I couldn't sign you in. Please close the app and open it again.",
        headers={"WWW-Authenticate": "Bearer"},
    )


async def current_device(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> str:
    if creds is None:
        raise _unauthorized("Missing bearer token.")
    try:
        device_id = decode_token(creds.credentials, request.app.state.settings)
    except jwt.PyJWTError:
        raise _unauthorized("Invalid or expired token.")
    request.state.device_hash = hashlib.sha256(device_id.encode()).hexdigest()[:8]  # for logs only
    return device_id
