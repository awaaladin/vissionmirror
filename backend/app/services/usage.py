"""What each person does, counted, and the same fair daily share for everyone.

Every AI action (describing a photo, reading, a follow-up question, a spoken answer) is counted against the person who made it:
a signed-in account counts across all its devices, a guest counts per device. The same limit applies to everyone: there are no tiers.
The counts are kept for 40 days so that, after a week of real use, the cost per person is a fact and not a guess
(see scripts/usage_report.py and docs/PRICING.md).

Nothing identifying is stored here: a person is a keyed hash of their account or device ID, and the day runs on Nigerian time.
"""

import datetime as dt
import hashlib
import hmac
import logging
from contextlib import asynccontextmanager

from redis.asyncio import Redis

from app.core.errors import APIError

log = logging.getLogger("visionmirror.usage")

LAGOS = dt.timezone(dt.timedelta(hours=1))  # Nigeria has no daylight saving
KEEP_SECONDS = 40 * 24 * 3600
KINDS = ("describe", "read", "ask", "assist")


class UsageMeter:
    def __init__(self, redis: Redis, secret: str, daily_limit: int, now=lambda: dt.datetime.now(dt.timezone.utc)):
        self.redis = redis
        self.secret = secret.encode()
        self.daily_limit = daily_limit
        self.now = now

    def day(self) -> str:
        return self.now().astimezone(LAGOS).strftime("%Y%m%d")

    def _who(self, subject: str) -> str:
        return hmac.new(self.secret, subject.encode(), hashlib.sha256).hexdigest()[:16]

    def _key(self, subject: str) -> str:
        return f"usage:{self.day()}:{self._who(subject)}"

    def hours_until_reset(self) -> int:
        local = self.now().astimezone(LAGOS)
        midnight = (local + dt.timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
        return max(1, round((midnight - local).total_seconds() / 3600))

    async def used_today(self, subject: str) -> int:
        try:
            return sum(int(v) for v in await self.redis.hvals(self._key(subject)))
        except Exception:  # noqa: BLE001 - counting must never be the reason someone is refused
            log.exception("could not read usage")
            return 0

    def limit_error(self) -> APIError:
        hours = self.hours_until_reset()
        wait = "about an hour" if hours <= 1 else f"about {hours} hours"
        return APIError(
            429,
            "fair_use_limit",
            f"The daily fair-use limit of {self.daily_limit} actions has been reached.",
            f"You've reached today's fair share of {self.daily_limit} looks and answers. It keeps VisionMirror fair and affordable for everyone. "
            f"It starts again at midnight, in {wait}. Thank you for understanding.",
            headers={"Retry-After": str(hours * 3600)},
        )

    async def check(self, subject: str) -> None:
        if await self.used_today(subject) >= self.daily_limit:
            raise self.limit_error()

    async def add(self, subject: str, kind: str) -> None:
        day = self.day()
        key = self._key(subject)
        try:
            async with self.redis.pipeline(transaction=False) as pipe:
                pipe.hincrby(key, kind, 1)
                pipe.expire(key, KEEP_SECONDS)
                pipe.sadd(f"usage_people:{day}", self._who(subject))
                pipe.expire(f"usage_people:{day}", KEEP_SECONDS)
                pipe.hincrby(f"usage_total:{day}", kind, 1)
                pipe.expire(f"usage_total:{day}", KEEP_SECONDS)
                await pipe.execute()
        except Exception:  # noqa: BLE001
            log.exception("could not count an action")

    @asynccontextmanager
    async def action(self, subject: str, kind: str):
        """Refuse politely when today's share is used up; count the action only if it worked (an outage costs the person nothing)."""
        await self.check(subject)
        yield
        await self.add(subject, kind)
