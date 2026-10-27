"""One place that decides whether a paid feature may be used now, and counts it when it was.

    async with guard(request, subject, "describe"):
        return await service.describe(...)

What the paywall locks (when `require_subscription` is on): the AI that describes, reads and answers, and the cloud voices.
What it never locks: the emergency contact and texting your location, "where am I", the on-device obstacle and object
detection in Guide mode, voice commands and settings. Safety is not for sale.

1. An account needs access: a monthly payment, a card subscription, or its free trial. A guest gets a few tries a day and
   is then asked to make a free account (which starts the trial).
2. Everyone, the same: today's fair share of actions (`fair_use_daily_actions`). Over it, a kind spoken message says when it resets.
3. An action that fails because the AI was unavailable is not counted.
"""

import uuid
from contextlib import asynccontextmanager

from fastapi import Request

from app.core.errors import APIError
from app.services.user_store import AccountsUnavailable

ACCESS_CACHE_SECONDS = 60


def subscription_required(settings, guest: bool = False, trial_days: int = 0) -> APIError:
    price = f"{settings.price_ngn:,}"
    if guest:
        spoken = (
            "To keep going, please create your free account. "
            f"You get {settings.trial_days} days free, then it is {price} naira a month, the same for everyone. "
            "Say sign in, and I'll take you there."
        )
    else:
        spoken = (
            f"Your free time is over. To keep using VisionMirror, please subscribe. It is {price} naira a month, the same for everyone. "
            "Say subscribe, and I'll open our website for you. Your emergency contact and your location still work."
        )
    return APIError(402, "subscription_required", "An active VisionMirror subscription is needed.", spoken)


async def account_for(request: Request, subject: str):
    """The account behind this token, or None for a guest's device."""
    try:
        return await request.app.state.users.get(uuid.UUID(subject))
    except (ValueError, AccountsUnavailable):
        return None


async def access_state(request: Request, subject: str) -> str:
    """'open' (nothing is enforced), 'ok' (paid or on trial), 'guest' or 'unpaid'. Remembered for a minute."""
    settings = request.app.state.settings
    if not settings.require_subscription:
        return "open"
    redis = request.app.state.redis
    key = f"access:{subject}"
    try:
        cached = await redis.get(key)
        if cached:
            return cached.decode() if isinstance(cached, bytes) else cached
    except Exception:  # noqa: BLE001
        pass
    user = await account_for(request, subject)
    if user is None:
        state = "guest"
    else:
        ent = await request.app.state.entitlements.for_email(user.email, user.created_at)
        state = "ok" if ent.active else "unpaid"
    if state != "guest":  # a guest who makes an account must not wait a minute for it to count
        try:
            await redis.set(key, state, ex=ACCESS_CACHE_SECONDS)
        except Exception:  # noqa: BLE001
            pass
    return state


@asynccontextmanager
async def guard(request: Request, subject: str, kind: str):
    settings = request.app.state.settings
    meter = request.app.state.usage
    state = await access_state(request, subject)
    if state == "unpaid":
        raise subscription_required(settings)
    if state == "guest" and await meter.used_today(subject) >= settings.guest_actions_per_day:
        raise subscription_required(settings, guest=True)
    async with meter.action(subject, kind):
        yield
