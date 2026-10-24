"""One place that decides whether an AI action may happen now, and counts it when it did.

    async with guard(request, subject, "describe"):
        return await service.describe(...)

1. If subscriptions are being enforced (`require_subscription`): an account needs access (a monthly payment, a card subscription or its
   trial); a guest gets a few tries a day and is then asked to make an account and subscribe. Off until it is switched on.
2. Everyone, the same: today's fair share of actions (`fair_use_daily_actions`). Over it, a kind spoken message says when it resets.
3. An action that fails because the AI was unavailable is not counted.
"""

import uuid
from contextlib import asynccontextmanager

from fastapi import Request

from app.core.errors import APIError
from app.services.user_store import AccountsUnavailable


def subscription_required(settings) -> APIError:
    price = f"{settings.price_ngn:,}"
    return APIError(
        402,
        "subscription_required",
        "An active VisionMirror subscription is needed.",
        f"To keep using VisionMirror, please subscribe. It is {price} naira a month, the same for everyone. "
        "Say subscribe, and I'll open our website for you.",
    )


async def account_for(request: Request, subject: str):
    """The account behind this token, or None for a guest's device."""
    try:
        return await request.app.state.users.get(uuid.UUID(subject))
    except (ValueError, AccountsUnavailable):
        return None


@asynccontextmanager
async def guard(request: Request, subject: str, kind: str):
    settings = request.app.state.settings
    meter = request.app.state.usage
    if settings.require_subscription:
        user = await account_for(request, subject)
        if user is None:
            if await meter.used_today(subject) >= settings.guest_actions_per_day:
                raise subscription_required(settings)
        elif not (await request.app.state.entitlements.for_email(user.email, user.created_at)).active:
            raise subscription_required(settings)
    async with meter.action(subject, kind):
        yield
