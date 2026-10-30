"""Signed, single-use, one-hour tokens for the forgotten-password link.

The token embeds a fingerprint of the current password hash, so the moment the password changes the
same link stops working (that is what makes it single use) without storing anything."""

import datetime as dt
import hashlib
import hmac
import uuid

import jwt

from app.core.config import Settings

RESET = "password_reset"
RESET_MINUTES = 60


def _fingerprint(password_hash: str) -> str:
    return hashlib.sha256((password_hash or "").encode()).hexdigest()[:16]


def create_reset_token(user_id: uuid.UUID, password_hash: str, settings: Settings) -> str:
    now = dt.datetime.now(dt.timezone.utc)
    claims = {
        "sub": str(user_id),
        "typ": RESET,
        "pf": _fingerprint(password_hash),
        "iat": now,
        "exp": now + dt.timedelta(minutes=RESET_MINUTES),
        "jti": str(uuid.uuid4()),
    }
    return jwt.encode(claims, settings.secret_key, algorithm="HS256")


def read_reset_token(token: str, settings: Settings) -> tuple[uuid.UUID, str] | None:
    """(user id, password fingerprint) if the token is genuine, unexpired and a reset token; else None."""
    try:
        claims = jwt.decode(token, settings.secret_key, algorithms=["HS256"], options={"require": ["exp", "sub"]})
        if claims.get("typ") != RESET:
            return None
        return uuid.UUID(claims["sub"]), str(claims.get("pf", ""))
    except (jwt.PyJWTError, ValueError, KeyError):
        return None


def matches(fingerprint: str, password_hash: str) -> bool:
    return hmac.compare_digest(fingerprint, _fingerprint(password_hash))
