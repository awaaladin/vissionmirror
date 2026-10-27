from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    secret_key: str = Field(min_length=32)
    redis_url: str = "redis://localhost:6379/0"

    vision_provider: Literal["mock", "anthropic", "gemini"] = "mock"
    vision_model: str = "claude-sonnet-5-5"
    gemini_api_key: str | None = None
    # The cheaper model answers first; anything it is unsure about (or cannot handle) goes to the stronger one.
    gemini_model: str = "gemini-3.5-flash-lite"
    gemini_strong_model: str = "gemini-3.5-flash"
    # Tried in order when the main model is busy (503) or rate limited (429). Comma separated; empty = none.
    gemini_fallback_models: str = "gemini-3.5-flash,gemini-3.8-flash"
    gemini_thinking_level: Literal["minimal", "low", "medium", "high"] = "medium"
    vision_effort: Literal["low", "medium", "high"] = "medium"
    anthropic_api_key: str | None = None
    mock_scenario: Literal["outfit", "stain", "clash", "dark", ""] = ""

    token_ttl_seconds: int = 24 * 3600
    # Signed-in accounts stay signed in longer: typing a password again is hard without sight.
    user_token_ttl_seconds: int = 30 * 24 * 3600
    # Postgres connection string for accounts. Empty = accounts off (guest mode still works).
    database_url: str | None = None
    session_ttl_seconds: int = 600
    max_image_bytes: int = 5 * 1024 * 1024

    # Requests per minute. Each applies per device and per IP; whichever trips first returns 429.
    rate_general_device_per_min: int = 60
    rate_general_ip_per_min: int = 120
    rate_describe_device_per_min: int = 6
    rate_describe_ip_per_min: int = 20
    rate_ask_device_per_min: int = 20
    rate_ask_ip_per_min: int = 60
    rate_auth_ip_per_min: int = 20  # token minting is free, so it is limited per IP only
    rate_account_ip_per_min: int = 10  # sign up / sign in / account calls, per IP
    login_max_failures: int = 8  # wrong passwords per email before it is locked for login_lock_seconds
    login_lock_seconds: int = 900

    # Azure neural voice (en-NG) for natural Nigerian speech. Empty = the app uses the phone's own voice.
    azure_speech_key: str | None = None
    azure_speech_region: str = "westeurope"
    azure_speech_voice: str = "en-NG-EzinneNeural"  # Adanna
    azure_speech_voice_male: str = "en-NG-AbeoNeural"  # Bola
    rate_speech_device_per_min: int = 120  # phrases are fetched ahead of time, so this must be roomy
    rate_speech_ip_per_min: int = 300

    # VoiceVerse translation API for Hausa, Yoruba, Igbo and Ibibio. Empty = those languages fall back to English.
    voiceverse_url: str | None = None
    voiceverse_api_key: str | None = None
    voiceverse_timeout_seconds: float = 20.0

    # Paystack, for one-time support payments (shared account; references start with VISIONMIRROR-).
    # Empty = POST /v1/support/checkout answers 503.
    # Read from PAYSTACK_SECRET_KEY, or PAYSTACK_API / PAYSTACK_SECRET (names the other apps on the shared account use).
    paystack_secret_key: str = Field(
        default="", validation_alias=AliasChoices("paystack_secret_key", "paystack_api", "paystack_secret"),
    )
    paystack_base_url: str = "https://api.paystack.co"
    # This API's public URL: Paystack sends the payer back to {public_base_url}/v1/support/return.
    public_base_url: str = "http://localhost:8000"
    # Where the payer lands after Paystack (the thank-you page on the website). Empty = this API's own /v1/support/return page.
    support_return_url: str = ""
    # One price for everyone, no tiers: naira a month. A monthly payment buys subscription_days days of access.
    price_ngn: int = 4500
    subscription_days: int = 31
    # Paystack plan (PLN_...) for the card subscription that renews itself. Made once by scripts/paystack_plan.py.
    paystack_plan_code: str = ""
    # When True, an account without access (payment, subscription or trial) is asked to subscribe. Off until switched on.
    require_subscription: bool = False
    trial_days: int = 7
    guest_actions_per_day: int = 3  # only when require_subscription is on: a few tries before making an account
    site_url: str = "https://visionmirror-app.vercel.app"  # where people pay
    support_min_ngn: int = 500
    support_max_ngn: int = 1_000_000

    # Global ceiling on AI calls (describe + ask) per UTC day, so a demo can't run up a bill.
    # (A safety ceiling for everyone together. Each person also has their own fair share, below.)
    daily_ai_call_limit: int = 20000
    # The same fair share for every person, counted per account (or per device for a guest), on Nigerian time.
    fair_use_daily_actions: int = 60
    # Spoken pieces a person gets in the real Hausa/Yoruba/Igbo voice each day (it costs about as much as the AI). After that: the phone's voice.
    local_voice_daily_pieces: int = 40

    # Comma-separated exact origins allowed by CORS, e.g. "https://app.example.com,http://localhost:5173".
    # Empty means no browser origin is allowed (native apps don't need CORS).
    cors_origins: str = ""
    # True only behind a proxy you control (Render, Railway, Fly): takes the client IP from X-Forwarded-For.
    trust_proxy_headers: bool = False
    log_level: str = "INFO"

    @property
    def cors_origin_list(self) -> list[str]:
        origins = [o.strip().rstrip("/") for o in self.cors_origins.split(",") if o.strip()]
        if "*" in origins:
            raise ValueError("CORS_ORIGINS must list exact origins; '*' is not allowed")
        return origins


@lru_cache
def get_settings() -> Settings:
    return Settings()
