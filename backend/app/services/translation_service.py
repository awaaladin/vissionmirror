"""Translation of the AI's spoken output into Nigerian languages, through the VoiceVerse API.

The vision model always writes English; for Hausa, Yoruba, Igbo and Ibibio the English is translated afterwards.
The model is never trusted to write those languages itself, and a failed translation never breaks a request:
the caller keeps the English and tells the user.

Contract used (VoiceVerse `POST /v1/translate (needs a "vv_..." API key, sent as a Bearer token)`):
    request  {"text": str (1..1000), "source": "en", "target": "ha"|"yo"|"ig"|"ibb"}
    response {"text": str, "translated": bool, "engine": str, "reason": str | null}
If VoiceVerse publishes a different consumable API, only `_call` needs to change.
"""

import logging
import re
from typing import Protocol

import httpx

log = logging.getLogger("visionmirror")

# ISO 639 codes VoiceVerse understands. Anything else is passed to the vision model as before.
TRANSLATED_LANGUAGES = frozenset({"ha", "yo", "ig", "ibb"})

MAX_CHUNK = 900  # VoiceVerse accepts 1000 characters per call; stay clear of the edge

# Spoken to the user, in English, when the translation could not be done.
FALLBACK_NOTICE = "I can't translate right now, so I'll speak in English."


def translation_target(language: str) -> str | None:
    """'ha', 'ha-NG' -> 'ha'. None when this language is not handled by translation."""
    base = language.split("-")[0].lower()
    return base if base in TRANSLATED_LANGUAGES else None


def chunk_sentences(text: str, limit: int = MAX_CHUNK) -> list[str]:
    """Split on sentence ends so each piece fits the API limit and no sentence is cut in half."""
    chunks: list[str] = []
    current = ""
    for sentence in re.split(r"(?<=[.!?])\s+", text.strip()):
        if not sentence:
            continue
        while len(sentence) > limit:  # a single monster sentence: cut at the last space before the limit
            cut = sentence.rfind(" ", 0, limit)
            cut = cut if cut > 0 else limit
            if current:
                chunks.append(current)
                current = ""
            chunks.append(sentence[:cut])
            sentence = sentence[cut:].lstrip()
        if current and len(current) + 1 + len(sentence) > limit:
            chunks.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}".strip()
    if current:
        chunks.append(current)
    return chunks


class Translator(Protocol):
    async def translate(self, text: str, target: str) -> str | None:
        """English -> target. None when it could not be translated (never raises)."""


class NoTranslator:
    async def translate(self, text: str, target: str) -> str | None:
        return None


class VoiceVerseTranslator:
    def __init__(self, base_url: str, api_key: str | None = None, timeout: float = 20.0,
                 client: httpx.AsyncClient | None = None):
        self._url = base_url.rstrip("/") + "/v1/translate"
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.AsyncClient(timeout=timeout)
        self._cache: dict[tuple[str, str], str] = {}

    async def translate(self, text: str, target: str) -> str | None:
        text = text.strip()
        if not text:
            return text
        parts: list[str] = []
        for chunk in chunk_sentences(text):
            out = await self._translate_chunk(chunk, target)
            if out is None:
                return None  # half a description in another language is worse than all of it in English
            parts.append(out)
        return " ".join(parts)

    async def _translate_chunk(self, chunk: str, target: str) -> str | None:
        key = (target, chunk)
        if key in self._cache:
            return self._cache[key]
        out = await self._call(chunk, target)
        if out is not None:
            if len(self._cache) >= 500:
                self._cache.pop(next(iter(self._cache)))
            self._cache[key] = out
        return out

    async def _call(self, chunk: str, target: str) -> str | None:
        body = {"text": chunk, "source": "en", "target": target}
        for attempt in range(2):  # one retry: mobile users hit this over weak links
            try:
                r = await self._client.post(self._url, json=body, headers=self._headers)
                if r.status_code in (502, 503, 504) and attempt == 0:
                    continue
                if r.status_code != 200:
                    log.warning("translation failed", extra={"ctx": {"status": r.status_code, "target": target}})
                    return None
                data = r.json()
                text = data.get("text")
                if data.get("translated") is True and isinstance(text, str) and text.strip():
                    return text.strip()
                log.warning("translation not available", extra={"ctx": {"reason": data.get("reason"), "target": target}})
                return None
            except (httpx.HTTPError, ValueError) as e:
                if attempt == 1:
                    log.warning("translation error", extra={"ctx": {"type": type(e).__name__, "target": target}})
        return None

    async def aclose(self) -> None:
        await self._client.aclose()
