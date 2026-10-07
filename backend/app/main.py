import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from redis.asyncio import Redis
from redis.exceptions import RedisError
from starlette.formparsers import MultiPartParser

from app.ai.provider import VisionProvider, build_provider
from app.api.v1 import ask, auth, describe, health, session
from app.core.config import Settings, get_settings
from app.core.errors import APIError, api_error_handler
from app.core.logging import setup_logging
from app.core.middleware import BodyLimitMiddleware, RequestContextMiddleware, SecurityHeadersMiddleware
from app.core.rate_limit import DailyCap, RateLimiter
from app.services.describe_service import DescribeService
from app.services.session_store import SessionStore

log = logging.getLogger("visionmirror")


async def redis_error_handler(_, exc: RedisError):
    log.error("redis error", extra={"ctx": {"type": type(exc).__name__}})
    return await api_error_handler(
        _,
        APIError(
            503,
            "service_unavailable",
            "The session store is unavailable.",
            "Something went wrong on my side. Please try again in a moment.",
        ),
    )


def create_app(
    settings: Settings | None = None,
    redis: Redis | None = None,
    provider: VisionProvider | None = None,
) -> FastAPI:
    """Dependencies are injectable so tests can use fakeredis and MockProvider."""
    settings = settings or get_settings()
    setup_logging(settings.log_level)
    owns_redis = redis is None
    redis = redis or Redis.from_url(settings.redis_url)
    provider = provider or build_provider(settings)
    cors_origins = settings.cors_origin_list  # validates at startup

    # Starlette spools uploads bigger than 1 MB to a temp file on disk. Photos must stay in memory only.
    MultiPartParser.spool_max_size = settings.max_image_bytes + 1024 * 1024

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        if owns_redis:
            await redis.aclose()

    app = FastAPI(title="VisionMirror AI", version="1.0.0", lifespan=lifespan)
    app.state.settings = settings
    app.state.limiter = RateLimiter(redis, settings.secret_key)
    app.state.store = SessionStore(redis, settings.session_ttl_seconds)
    app.state.cap = DailyCap(redis, settings.daily_ai_call_limit)
    app.state.service = DescribeService(provider, app.state.store, app.state.cap)
    app.add_exception_handler(APIError, api_error_handler)
    app.add_exception_handler(RedisError, redis_error_handler)

    for module in (health, auth, describe, ask, session):
        app.include_router(module.router, prefix="/v1")

    # Added last = outermost. Order outside-in: request context, CORS, security headers, body limit.
    app.add_middleware(BodyLimitMiddleware, max_image_bytes=settings.max_image_bytes)
    app.add_middleware(SecurityHeadersMiddleware)
    if cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=cors_origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "DELETE"],
            allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
            expose_headers=["Retry-After", "X-Request-ID"],
            max_age=600,
        )
    app.add_middleware(RequestContextMiddleware)
    return app


def __getattr__(name: str):
    # `uvicorn app.main:app` builds the app lazily, so importing this module needs no env vars.
    if name == "app":
        return create_app()
    raise AttributeError(name)
