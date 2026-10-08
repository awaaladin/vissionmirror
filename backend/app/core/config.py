from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    secret_key: str = Field(min_length=32)
    redis_url: str = "redis://localhost:6379/0"

    vision_provider: Literal["mock", "anthropic", "gemini"] = "mock"
    vision_model: str = "claude-sonnet-5-5"
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-3.5-flash"
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

    # Global ceiling on AI calls (describe + ask) per UTC day, so a demo can't run up a bill.
    daily_ai_call_limit: int = 500

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
