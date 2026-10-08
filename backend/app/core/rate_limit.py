import datetime as dt
import hashlib
import hmac
import logging
import time
from collections.abc import Callable

from fastapi import Depends, Request
from redis.asyncio import Redis

from app.core.config import Settings
from app.core.errors import APIError
from app.core.security import current_device

log = logging.getLogger("visionmirror.limits")

WINDOW_SECONDS = 60


def rate_limited(retry_after: int, what: str = "") -> APIError:
    wait = max(1, retry_after)
    return APIError(
        429,
        "rate_limited",
        f"Too many requests{what}. Retry in {wait}s.",
        f"You're going a little fast. Please wait {wait} seconds and try again.",
        headers={"Retry-After": str(wait)},
    )


class RateLimiter:
    """Fixed-window counters in Redis (one INCR per check, keys expire on their own)."""

    def __init__(self, redis: Redis, secret: str, clock: Callable[[], float] = time.time):
        self.redis = redis
        self.secret = secret.encode()
        self.clock = clock

    def _ident(self, value: str) -> str:
        # Never store raw IPs or device IDs in Redis keys.
        return hmac.new(self.secret, value.encode(), hashlib.sha256).hexdigest()[:20]

    async def check(
        self, scope: str, kind: str, ident: str, limit: int, window: int = WINDOW_SECONDS
    ) -> int | None:
        """Count one hit. Returns seconds until the window resets if over the limit, else None."""
        now = int(self.clock())
        key = f"rl:{scope}:{kind}:{self._ident(ident)}:{now // window}"
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.incr(key)
            pipe.expire(key, window + 1)
            count, _ = await pipe.execute()
        if count > limit:
            log.warning("rate limit hit", extra={"ctx": {"scope": scope, "by": kind}})
            return window - (now % window)
        return None


class LoginThrottle:
    """Slows password guessing: after too many wrong passwords an email is locked for a while.

    Counts failures only, keyed by a one-way hash of the email, so it also protects accounts that do
    not exist from being probed, without revealing which emails are registered.
    """

    def __init__(self, redis: Redis, secret: str, max_failures: int, lock_seconds: int):
        self.redis = redis
        self.secret = secret.encode()
        self.max_failures = max_failures
        self.lock_seconds = lock_seconds

    def _key(self, email: str) -> str:
        return "login_fail:" + hmac.new(self.secret, email.lower().encode(), hashlib.sha256).hexdigest()[:24]

    async def locked_for(self, email: str) -> int | None:
        """Seconds left on the lock, or None if the email may try."""
        key = self._key(email)
        count = await self.redis.get(key)
        if count is not None and int(count) >= self.max_failures:
            return max(1, await self.redis.ttl(key))
        return None

    async def failed(self, email: str) -> None:
        key = self._key(email)
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.incr(key)
            pipe.expire(key, self.lock_seconds)
            await pipe.execute()

    async def succeeded(self, email: str) -> None:
        await self.redis.delete(self._key(email))


class DailyCap:
    """Global cap on AI calls per UTC day. 0 means no AI calls are allowed."""

    def __init__(
        self,
        redis: Redis,
        limit: int,
        now: Callable[[], dt.datetime] = lambda: dt.datetime.now(dt.timezone.utc),
    ):
        self.redis = redis
        self.limit = limit
        self.now = now

    def _key(self) -> str:
        return f"daily_ai:{self.now():%Y-%m-%d}"

    async def reserve(self) -> None:
        key = self._key()
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.incr(key)
            pipe.expire(key, 2 * 24 * 3600)
            used, _ = await pipe.execute()
        if used > self.limit:
            await self.redis.decr(key)
            now = self.now()
            midnight = (now + dt.timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
            wait = max(1, int((midnight - now).total_seconds()))
            log.warning("daily AI cap reached", extra={"ctx": {"limit": self.limit}})
            raise APIError(
                429,
                "daily_limit_reached",
                "The daily AI call limit has been reached.",
                "I've reached my limit for today. Please try again tomorrow.",
                headers={"Retry-After": str(wait)},
            )

    async def release(self) -> None:
        """Give a reserved slot back when the AI call failed, so outages don't burn the budget."""
        await self.redis.decr(self._key())


def client_ip(request: Request, trust_proxy: bool) -> str:
    if trust_proxy:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            # The last entry is the address the nearest trusted proxy saw; earlier ones are client-supplied.
            return forwarded.split(",")[-1].strip()
    return request.client.host if request.client else "unknown"


async def enforce(request: Request, scope: str, device_id: str | None, settings: Settings) -> None:
    limiter: RateLimiter = request.app.state.limiter
    ip = client_ip(request, settings.trust_proxy_headers)
    s = settings
    limits = {
        "general": (s.rate_general_device_per_min, s.rate_general_ip_per_min),
        "describe": (s.rate_describe_device_per_min, s.rate_describe_ip_per_min),
        "ask": (s.rate_ask_device_per_min, s.rate_ask_ip_per_min),
        "auth": (None, s.rate_auth_ip_per_min),
        "account": (None, s.rate_account_ip_per_min),
    }
    device_limit, ip_limit = limits[scope]
    waits = []
    if device_id is not None and device_limit is not None:
        waits.append(await limiter.check(scope, "device", device_id, device_limit))
    waits.append(await limiter.check(scope, "ip", ip, ip_limit))
    exceeded = [w for w in waits if w is not None]
    if exceeded:
        raise rate_limited(max(exceeded))


def limited(scope: str):
    """Dependency: authenticate, then apply the named rate limit (401 always wins over 429)."""

    async def dependency(request: Request, device_id: str = Depends(current_device)) -> None:
        await enforce(request, scope, device_id, request.app.state.settings)

    return dependency


async def limit_auth(request: Request) -> None:
    await enforce(request, "auth", None, request.app.state.settings)


async def limit_account(request: Request) -> None:
    await enforce(request, "account", None, request.app.state.settings)
