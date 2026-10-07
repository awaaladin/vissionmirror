"""Google Gemini provider (free tier available from Google AI Studio). Plain REST via httpx."""

import asyncio
import json

import httpx
from pydantic import BaseModel

from app.ai.structured import StructuredProvider, log, unavailable

BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
RETRY_STATUSES = {429, 500, 502, 503, 504}
RETRY_DELAYS = (1.0, 3.0)  # seconds before retry 1 and retry 2
MAX_OUTPUT_TOKENS = 8192  # Gemini 3 spends part of this on hidden thinking


class GeminiProvider(StructuredProvider):
    def __init__(
        self,
        *,
        api_key: str,
        model: str = "gemini-3.5-flash",
        thinking_level: str = "medium",
        client: httpx.AsyncClient | None = None,
    ):
        self.model = model
        self.thinking_level = thinking_level
        self.api_key = api_key
        self.client = client or httpx.AsyncClient(timeout=httpx.Timeout(90.0, connect=15.0))

    async def _post(self, body: dict) -> httpx.Response:
        """POST with a few retries for transient trouble (busy server, rate limit, dropped connection)."""
        for attempt in range(len(RETRY_DELAYS) + 1):
            try:
                # Key goes in a header, never the URL, so it can't leak into access logs.
                r = await self.client.post(
                    f"{BASE_URL}/models/{self.model}:generateContent",
                    headers={"x-goog-api-key": self.api_key},
                    json=body,
                )
                failure = None if r.status_code not in RETRY_STATUSES else f"status {r.status_code}"
            except httpx.HTTPError as exc:
                r, failure = None, type(exc).__name__
            if failure is None:
                break
            log.warning("gemini transient failure (%s), attempt %d", failure, attempt + 1)
            if attempt < len(RETRY_DELAYS):
                await asyncio.sleep(RETRY_DELAYS[attempt])
        if r is None:
            raise unavailable("Could not reach the AI provider.")
        if r.status_code != 200:
            log.error("gemini status error %s", r.status_code)
            raise unavailable(f"AI provider returned HTTP {r.status_code}.")
        return r

    async def _call(self, system: str, messages: list[dict], model_cls: type[BaseModel]) -> str:
        # JSON mode guarantees parseable JSON; the schema is spelled out in the prompt and validated by Pydantic.
        schema = json.dumps(model_cls.model_json_schema())
        body = {
            "systemInstruction": {"parts": [{"text": f"{system}\n\nJSON schema to follow exactly:\n{schema}"}]},
            "contents": [_to_gemini(m) for m in messages],
            "generationConfig": {
                "responseMimeType": "application/json",
                "maxOutputTokens": MAX_OUTPUT_TOKENS,
                "temperature": 0.4,
                "thinkingConfig": {"thinkingLevel": self.thinking_level},
            },
        }
        r = await self._post(body)

        data = r.json()
        if data.get("promptFeedback", {}).get("blockReason"):
            raise unavailable("The AI provider declined this request.")
        candidates = data.get("candidates") or []
        if not candidates:
            raise unavailable("The AI response had no candidates.")
        cand = candidates[0]
        if cand.get("finishReason") in {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "IMAGE_SAFETY"}:
            raise unavailable("The AI provider declined this request.")
        if cand.get("finishReason") == "MAX_TOKENS":
            raise unavailable("The AI response was cut off.")
        texts = [p["text"] for p in cand.get("content", {}).get("parts", []) if "text" in p and not p.get("thought")]
        if not texts:
            raise unavailable("The AI response had no text.")
        return "".join(texts)


def _to_gemini(message: dict) -> dict:
    parts = []
    for part in message["parts"]:
        if part["type"] == "image":
            parts.append({"inline_data": {"mime_type": part["media_type"], "data": part["data"]}})
        else:
            parts.append({"text": part["text"]})
    return {"role": "model" if message["role"] == "assistant" else "user", "parts": parts}
