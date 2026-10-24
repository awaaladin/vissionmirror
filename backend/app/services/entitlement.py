"""Does this person have VisionMirror paid for? One price for everyone: 4,500 naira a month.

Access comes from any of these, whichever runs out last:
  - a monthly payment saved in `visionmirror.payments` (a payment buys `subscription_days` days, and payments add up);
  - an active Paystack card subscription to the VisionMirror plan (it renews itself; asked of Paystack and remembered for a few minutes);
  - a free trial counted from the day the account was made (`trial_days`).

Whether the app REFUSES people without access is a switch (`require_subscription`), off until it is turned on.
"""

import datetime as dt
import hashlib
import json
import logging
from dataclasses import dataclass

from app.services.payment_store import PaymentStore, best_effort

log = logging.getLogger("visionmirror.entitlement")

PAID_PURPOSES = ("MONTH", "SUBSCRIBE")
GRACE_DAYS = 3  # a card renewal is sometimes a day or two late; nobody loses access for that


@dataclass
class Entitlement:
    active: bool
    until: dt.datetime | None
    source: str  # "paid", "subscription", "trial" or "none"
    trial_until: dt.datetime | None = None


def paid_until(rows, days: int) -> dt.datetime | None:
    """Payments add up: each one buys `days` days, starting when the previous one ends (or when it was paid, if later)."""
    until: dt.datetime | None = None
    for r in sorted((r for r in rows if r.status == "success" and r.paid_at and r.purpose in PAID_PURPOSES), key=lambda r: r.paid_at):
        start = r.paid_at if until is None or r.paid_at > until else until
        until = start + dt.timedelta(days=days)
    return until


def _when(value) -> dt.datetime | None:
    try:
        return dt.datetime.fromisoformat(str(value).replace("Z", "+00:00")) if value else None
    except ValueError:
        return None


class Entitlements:
    def __init__(self, settings, payments: PaymentStore, redis, paystack_factory, now=lambda: dt.datetime.now(dt.timezone.utc)):
        self.settings, self.payments, self.redis, self.paystack, self.now = settings, payments, redis, paystack_factory, now

    async def _card_subscription_until(self, email: str) -> dt.datetime | None:
        s = self.settings
        if not (s.paystack_plan_code and s.paystack_secret_key):
            return None
        key = "entitlement:card:" + hashlib.sha256(email.lower().encode()).hexdigest()[:24]
        try:
            cached = await self.redis.get(key)
            if cached is not None:
                return _when(cached.decode() if isinstance(cached, bytes) else cached)
        except Exception:  # noqa: BLE001
            pass
        until = None
        try:
            customer = await self.paystack().customer(email)
            plan_id = await self.paystack().plan_id(s.paystack_plan_code)
            for sub in customer.get("subscriptions") or []:
                plan = sub.get("plan")
                plan_ok = plan == plan_id or (isinstance(plan, dict) and plan.get("plan_code") == s.paystack_plan_code)
                nxt = _when(sub.get("next_payment_date"))
                if plan_ok and sub.get("status") == "active" and nxt:
                    until = max(until, nxt + dt.timedelta(days=GRACE_DAYS)) if until else nxt + dt.timedelta(days=GRACE_DAYS)
                elif plan_ok and sub.get("status") == "non-renewing" and nxt:  # cancelled, paid up to the next date
                    until = max(until, nxt) if until else nxt
        except Exception as exc:  # noqa: BLE001 - Paystack being unreachable must not lock anyone out
            log.warning("card subscription lookup failed: %s", type(exc).__name__)
            return None
        try:
            await self.redis.set(key, until.isoformat() if until else "", ex=600)
        except Exception:  # noqa: BLE001
            pass
        return until

    async def for_email(self, email: str, account_created: dt.datetime | None = None) -> Entitlement:
        s, now = self.settings, self.now()
        rows = await best_effort(self.payments.for_email(email), "entitlement payments") or []
        candidates: list[tuple[dt.datetime, str]] = []
        if (until := paid_until(rows, s.subscription_days)) is not None:
            candidates.append((until, "paid"))
        if (card := await self._card_subscription_until(email)) is not None:
            candidates.append((card, "subscription"))
        trial_until = account_created + dt.timedelta(days=s.trial_days) if (account_created and s.trial_days > 0) else None
        if trial_until is not None:
            candidates.append((trial_until, "trial"))
        if not candidates:
            return Entitlement(False, None, "none", trial_until)
        until, source = max(candidates, key=lambda c: c[0])
        return Entitlement(until > now, until, source if until > now else "none", trial_until)
