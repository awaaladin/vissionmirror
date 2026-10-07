# VisionMirror AI: backend

FastAPI service behind the VisionMirror talking mirror. Full request/response reference: [docs/api.md](../docs/api.md).

## Run locally (no Docker)

```bash
python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
cp .env.example .env                                    # then set SECRET_KEY
python scripts/dev_redis.py                             # terminal 1: in-memory Redis on :6379
uvicorn app.main:app --reload                           # terminal 2
```

API at http://localhost:8000, Swagger UI at http://localhost:8000/docs.
`VISION_PROVIDER=mock` (the default) needs no API key. `scripts/dev_redis.py` is for development only; use a real Redis in production.

Docker is optional: `docker compose up --build` also works if you have it.

## Real AI

Two real providers; pick one in `.env`:

- **Gemini (free tier):** get a key at https://aistudio.google.com/apikey (Google account, no card), then `VISION_PROVIDER=gemini` and `GEMINI_API_KEY=...`. Model via `GEMINI_MODEL`. Free-tier privacy caveat: see [docs/privacy.md](../docs/privacy.md).
- **Anthropic (paid):** `VISION_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=...` (model `VISION_MODEL`, default `claude-sonnet-5-5`; depth `VISION_EFFORT`). API credits are bought separately at console.anthropic.com; a Claude Code or Claude.ai subscription does not include API access.

To try photos from the command line (costs real API calls):

```bash
python scripts/try_photo.py ../samples/woman_dress.jpg --detail standard --ask "Is my dress clean?"
```

Prompts live in `app/ai/prompts.py` (versioned; safety rules are pinned by tests).

## Deploy

Step-by-step for Render (free tier, HTTPS), plus Railway and Fly, the full env var list and a launch checklist: [docs/deploy.md](../docs/deploy.md). After deploying, verify with:

```bash
python scripts/smoke_test.py https://YOUR-SERVICE.onrender.com --photo ../samples/street_woman.jpg
```

## Tests

```bash
pytest
```

Tests use `fakeredis` and `MockProvider`: no Redis, network or API key needed.

## Try it with curl

```bash
BASE=http://localhost:8000

# 1. Health
curl $BASE/v1/health

# 2. Get a token (device_id is a random UUID your app generates once)
TOKEN=$(curl -s -X POST $BASE/v1/auth/anon \
  -H 'Content-Type: application/json' \
  -d '{"device_id": "3f2b8c1e-9d4a-4c55-8a51-0e7d3c2b1a90"}' | python -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 3. Describe a photo
curl -s -X POST $BASE/v1/describe \
  -H "Authorization: Bearer $TOKEN" \
  -F image=@photo.jpg -F detail_level=standard -F language=en

# 4. Ask a follow-up (use the session_id from step 3)
curl -s -X POST $BASE/v1/ask \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"session_id": "SESSION_ID", "question": "Do my colours match?", "language": "en"}'

# 5. Delete the session
curl -i -X DELETE $BASE/v1/session/SESSION_ID -H "Authorization: Bearer $TOKEN"
```

In mock mode, force a scenario with `MOCK_SCENARIO=dark` (or `outfit`, `stain`, `clash`) in `.env`.

## Configuration

See [.env.example](.env.example). `SECRET_KEY` (32+ chars) is required; the app refuses to start without it.
Never commit `.env`.

## Privacy

Photos live in memory and in Redis only, with a 10-minute expiry. They are never written to disk or logged. Redis in `docker-compose.yml` has persistence turned off.
