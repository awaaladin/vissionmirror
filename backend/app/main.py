import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from redis.asyncio import Redis
from redis.exceptions import RedisError
from starlette.formparsers import MultiPartParser

from app.ai.provider import VisionProvider, build_provider
from app.api.v1 import account, ask, assist, auth, describe, health, read, session, speech, support, transcribe
from app.core.config import Settings, get_settings
from app.core.errors import APIError, api_error_handler
from app.core.logging import setup_logging
from app.core.middleware import BodyLimitMiddleware, RequestContextMiddleware, SecurityHeadersMiddleware
from app.core.rate_limit import DailyCap, LoginThrottle, RateLimiter
from app.services.assist_service import AssistService
from app.services.describe_service import DescribeService
from app.services.session_store import SessionStore
from app.services.payment_store import PaymentStore, build_payment_store
from app.services.transcription_service import NoTranscriber, Transcriber, VoiceVerseTranscriber
from app.services.speech_service import AzureSpeech, NoSpeech, RoutingSpeech, SpeechSynthesizer, VoiceVerseSpeech
from app.services.translation_service import NoTranslator, Translator, VoiceVerseTranslator
from app.services.user_store import AccountsUnavailable, NoUserStore, PostgresUserStore, UserStore

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


async def accounts_unavailable_handler(request, exc: AccountsUnavailable):
    log.error("accounts database unavailable")
    return await api_error_handler(
        request,
        APIError(
            503,
            "accounts_unavailable",
            "Accounts are not available right now.",
            "Accounts aren't available right now. You can still use the app as a guest.",
        ),
    )


def create_app(
    settings: Settings | None = None,
    redis: Redis | None = None,
    provider: VisionProvider | None = None,
    users: UserStore | None = None,
    translator: Translator | None = None,
    speech_service: SpeechSynthesizer | None = None,
    assist_service: AssistService | None = None,
    transcriber: Transcriber | None = None,
    payments: PaymentStore | None = None,
) -> FastAPI:
    """Dependencies are injectable so tests can use fakeredis and MockProvider."""
    settings = settings or get_settings()
    setup_logging(settings.log_level)
    owns_redis = redis is None
    redis = redis or Redis.from_url(settings.redis_url)
    provider = provider or build_provider(settings)
    cors_origins = settings.cors_origin_list  # validates at startup
    if translator is None:
        translator = (
            VoiceVerseTranslator(
                settings.voiceverse_url, settings.voiceverse_api_key, settings.voiceverse_timeout_seconds
            )
            if settings.voiceverse_url
            else NoTranslator()
        )

    # Starlette spools uploads bigger than 1 MB to a temp file on disk. Photos must stay in memory only.
    MultiPartParser.spool_max_size = settings.max_image_bytes + 1024 * 1024

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await app.state.users.close()
        if hasattr(app.state.speech, "aclose"):
            await app.state.speech.aclose()
        if hasattr(translator, "aclose"):
            await translator.aclose()
        if hasattr(app.state.transcriber, "aclose"):
            await app.state.transcriber.aclose()
        if owns_redis:
            await redis.aclose()

    app = FastAPI(title="VisionMirror AI", version="1.0.0", lifespan=lifespan)
    app.state.settings = settings
    app.state.redis = redis
    app.state.users = users or (PostgresUserStore(settings.database_url) if settings.database_url else NoUserStore())
    app.state.payments = payments or build_payment_store(app.state.users)
    app.state.login_throttle = LoginThrottle(
        redis, settings.secret_key, settings.login_max_failures, settings.login_lock_seconds
    )
    app.state.limiter = RateLimiter(redis, settings.secret_key)
    app.state.store = SessionStore(redis, settings.session_ttl_seconds)
    app.state.cap = DailyCap(redis, settings.daily_ai_call_limit)
    if speech_service is None:
        local = (
            AzureSpeech(
                settings.azure_speech_key, settings.azure_speech_region, settings.azure_speech_voice,
                settings.azure_speech_voice_male,
            )
            if settings.azure_speech_key
            else NoSpeech()
        )
        # Hausa, Yoruba and Igbo are pronounced by the VoiceVerse API, so VisionMirror carries no rules for them.
        speech_service = (
            RoutingSpeech(local, VoiceVerseSpeech(settings.voiceverse_url, settings.voiceverse_api_key, settings.voiceverse_timeout_seconds))
            if settings.voiceverse_url and settings.voiceverse_api_key
            else local
        )
    app.state.speech = speech_service
    app.state.transcriber = transcriber or (
        VoiceVerseTranscriber(settings.voiceverse_url, settings.voiceverse_api_key, settings.voiceverse_timeout_seconds)
        if settings.voiceverse_url and settings.voiceverse_api_key
        else NoTranscriber()
    )
    app.state.service = DescribeService(provider, app.state.store, app.state.cap, translator)
    app.state.assist = assist_service or AssistService(provider, app.state.cap, translator)
    app.add_exception_handler(APIError, api_error_handler)
    app.add_exception_handler(RedisError, redis_error_handler)
    app.add_exception_handler(AccountsUnavailable, accounts_unavailable_handler)

    for module in (health, auth, account, describe, ask, assist, session, speech, transcribe, read, support):
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
