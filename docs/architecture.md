# Architecture

```
Android app ──HTTPS──▶ FastAPI (/v1) ──▶ VisionProvider ──▶ Anthropic API (or MockProvider)
                          │
                          └──▶ Redis: sessions (10 min TTL)  [rate-limit counters, daily AI cap]
```

| Layer | Path | Job |
|-------|------|-----|
| API | `app/api/v1/` | Thin routers: parse, authenticate, call a service. |
| Services | `app/services/` | `DescribeService` (describe/ask flow), `SessionStore` (Redis), `image_utils` (size and type checks). |
| AI | `app/ai/` | `VisionProvider` interface, `MockProvider`, `GeminiProvider`, `AnthropicProvider` (shared JSON-validation and retry in `structured.py`), versioned `prompts.py`. |
| Core | `app/core/` | Settings from env, JWT auth, the shared `APIError`. |
| Schemas | `app/schemas/` | Pydantic models: the API contract. |

## Decisions

- **App factory with injection.** `create_app(settings, redis, provider)` lets tests swap in `fakeredis` and `MockProvider`.
- **Provider interface.** Services never import an AI SDK; swapping or adding providers touches only `app/ai/`.
- **Stateless API, state in Redis.** Any number of API instances can serve the same session.
- **Anonymous device tokens.** A signed JWT (HS256, 24 h) whose `sub` is the device ID. Sessions are bound to that device, so another device's token cannot read or delete them (it gets the same 404 as a missing session).
- **Every error has `spoken_text`**, so the app can always say something useful aloud.
- **Image type is checked by magic bytes**, not the client's Content-Type header.
- **No session for unusable photos.** `/describe` returns `session_id: null` when `usable` is false; there is nothing worth asking about, and nothing is stored.

## Hardening (Phase 3)

- **Middleware, outermost first:** request ID + logging, CORS (only if `CORS_ORIGINS` is set), security headers, body-size limit. All pure ASGI, in `app/core/middleware.py`.
- **Rate limits** (`app/core/rate_limit.py`): fixed one-minute windows, one Redis `INCR` each. Authentication runs first, so an invalid token is always `401`, never `429`. Token minting is limited per IP only, since a new device ID is free.
- **Daily AI cap:** a Redis counter per UTC day, checked immediately before each provider call. Slots are returned if the provider fails.
- **Client IP:** taken from the socket unless `TRUST_PROXY_HEADERS=true`, then from the last `X-Forwarded-For` entry (the one the trusted proxy appended).
- **CORS:** exact origins only; `*` is refused at startup; no credentials.
