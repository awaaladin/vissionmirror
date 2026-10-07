# VisionMirror API v1

Base URL: `http://localhost:8000` locally. All paths below are under `/v1`.
Interactive docs: `/docs` (Swagger) and `/openapi.json` (machine-readable schema; generate the Kotlin models from it if you like).

## Conventions

- **Auth:** every endpoint except `/health` and `/auth/anon` needs `Authorization: Bearer <token>`.
- **Errors** always have this shape, so the app can speak `spoken_text` without extra logic:
  ```json
  {"code": "session_not_found", "message": "Developer-facing detail.", "spoken_text": "Something safe to read aloud."}
  ```
  Exception: `422` validation errors use FastAPI's default `{"detail": [...]}` body. These mean a bug in the app, not a user problem.
- **`spoken_text` is the primary output.** Read it aloud with the device's text-to-speech. Everything else is for on-screen use or logic.
- Field names are `snake_case`. Note `color` (US) inside `outfit` items but `colour_harmony` (UK) at the top level; both are intentional and fixed.

## Limits

Defaults (the operator can change them). Limits are per minute, counted per device and per IP address; whichever trips first returns `429`.

| Endpoint | Per device | Per IP |
|----------|-----------|--------|
| `POST /auth/anon` | n/a | 20 |
| `POST /describe` | 6 | 20 |
| `POST /ask` | 20 | 60 |
| everything else | 60 | 120 |

`/health` is not limited. Failed requests count too. `/describe` takes about 10-15 seconds with the default settings (it inspects the outfit carefully), so show a "looking at your photo" message or sound. `/ask` is faster. `/describe` and `/ask` also draw on a global daily AI budget (500 calls by default); a request rejected for a bad photo, or an AI failure, does not use any of it.
Every response carries an `X-Request-ID` header; quote it when reporting a problem. Bodies larger than 5 MB plus a small overhead are rejected with `413` before they are read; JSON bodies are capped at 64 KB.

## Error codes

| HTTP | `code` | When |
|------|--------|------|
| 401 | `unauthorized` | Missing, invalid or expired token. Call `/auth/anon` again and retry once. |
| 404 | `session_not_found` | Session expired (10 min), was deleted, or belongs to another device. Take a new photo. |
| 413 | `image_too_large` | Image over 5 MB. |
| 415 | `unsupported_image` | Not a real JPEG, PNG or WebP (checked by file contents, not the Content-Type header). |
| 422 | n/a | Request failed validation. |
| 502 | `ai_unavailable` | The AI service failed or returned unusable output twice. Read `spoken_text` aloud and let her retry. Nothing is stored. |
| 429 | `rate_limited` | Too many requests from this device or network. `Retry-After` header gives seconds to wait; `spoken_text` is ready to read ("You're going a little fast. Please wait 12 seconds and try again."). |
| 429 | `daily_limit_reached` | The service's daily AI budget is used up. `Retry-After` is seconds until it resets (UTC midnight). Do not retry sooner; read `spoken_text` and stop. |
| 503 | `service_unavailable` | The session store is down. Retry shortly. |

---

## GET /v1/health

No auth.

**200**
```json
{"status": "ok", "version": "1"}
```

---

## POST /v1/auth/anon

Exchange a device ID for a short-lived token. Generate `device_id` once with `UUID.randomUUID()` and store it on the device.

**Request** (`application/json`)
```json
{"device_id": "3f2b8c1e-9d4a-4c55-8a51-0e7d3c2b1a90"}
```

**200**
```json
{"access_token": "eyJ...", "token_type": "bearer", "expires_in": 86400}
```

`expires_in` is in seconds (24 h). Request a new token when you get a `401` or shortly before expiry.
**422** if `device_id` is not a UUID.

---

## POST /v1/describe

`multipart/form-data`

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `image` | file | yes | JPEG, PNG or WebP, max 5 MB. |
| `detail_level` | `brief` \| `standard` \| `detailed` | no | Default `standard`. Controls the length of `spoken_text`. |
| `language` | string | no | Default `en`. Language code such as `en` or `pt-BR`. |

**200**
```json
{
  "session_id": "b1c5f3c2-6a7e-4c0a-9a43-2f4d1b7a8e11",
  "image_quality": {"usable": true, "issue": null, "advice": null},
  "summary": "Your white top looks fresh, but there may be a mark near the collar.",
  "outfit": [
    {"item": "top", "description": "Short-sleeved blouse", "color": "white", "pattern": "plain"}
  ],
  "hair_and_grooming": "Hair is tied back in a low ponytail.",
  "accessories": [],
  "issues": [
    {
      "severity": "medium",
      "what": "A small dark mark that could be a stain",
      "where": "left side of the collar",
      "suggestion": "Dab it with a damp cloth, or switch to another top."
    }
  ],
  "colour_harmony": {"verdict": "good", "explanation": "White and black is a classic pairing."},
  "spoken_text": "Your white blouse and black wide-leg trousers look crisp together. There is a small dark mark ...",
  "confidence": 0.7
}
```

| Field | Notes |
|-------|-------|
| `session_id` | Pass to `/ask` and `/session/{id}`. **`null` when `image_quality.usable` is false** (nothing is stored). |
| `image_quality.usable` | `false` if too dark, blurry, cropped, or no person. Then read `advice` aloud, let her try again, and do not show an outfit. |
| `image_quality.issue` | Short code, e.g. `too_dark`. May be `null`. |
| `outfit[].color`, `.pattern` | `null` when not visible. |
| `issues[].severity` | `low`, `medium` or `high`. Issues are ordered most important first. |
| `colour_harmony.verdict` | `good`, `mixed`, `clashing` or `unsure`. |
| `confidence` | 0.0 to 1.0. |

**Unusable photo example (200)**
```json
{
  "session_id": null,
  "image_quality": {"usable": false, "issue": "too_dark", "advice": "It's quite dark, try facing a window or turning on a light."},
  "summary": "", "outfit": [], "hair_and_grooming": "", "accessories": [], "issues": [],
  "colour_harmony": {"verdict": "unsure", "explanation": ""},
  "spoken_text": "It's quite dark, try facing a window or turning on a light.",
  "confidence": 0.0
}
```

Errors: `401`, `413`, `415`, `422`, `502`.

**Image handling.** The server applies the photo's rotation tag, shrinks it to 1568 px on the longest side, re-encodes it as JPEG and drops all metadata, so phone photos work as-is. The app should still downscale before upload (about 1600 px, JPEG quality 85 is plenty): it is faster on mobile data and keeps under the 5 MB limit. A corrupt or truncated file returns `415`.

---

## POST /v1/ask

Follow-up question about the same photo. Conversation context is kept, so "what about the other one?" works.

**Request** (`application/json`)
```json
{"session_id": "b1c5f3c2-6a7e-4c0a-9a43-2f4d1b7a8e11", "question": "Is that a stain on my collar?", "language": "en"}
```
`question`: 1 to 500 characters. `language` defaults to `en`.

**200**
```json
{"answer_text": "...", "spoken_text": "...", "confidence": 0.7}
```

Errors: `401`, `404` (`session_not_found`), `422`.

Each successful ask resets the session's 10-minute expiry, so an ongoing conversation is not cut off.

---

## DELETE /v1/session/{session_id}

Deletes the photo and conversation immediately. Call it when she leaves the mirror screen.

**204** no body. **404** if the session doesn't exist, has expired, or belongs to another device. Treat 404 as "already gone".

---

## Mock mode (for building the app)

With `VISION_PROVIDER=mock` the API returns canned results with no AI calls. Which scenario you get is decided by a hash of the image bytes (same photo, same answer), or fixed by `MOCK_SCENARIO=outfit|stain|clash|dark`. Scenarios: a good outfit, a possible stain, clashing colours, and an unusable dark photo.
