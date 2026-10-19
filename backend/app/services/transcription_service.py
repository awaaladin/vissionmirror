"""Speech to text for Hausa, Yoruba, Igbo (and English), through the VoiceVerse API.

VisionMirror carries no speech models for these languages. The phone sends a short recording; VoiceVerse
transcribes it (`POST /v1/transcribe?language=ha|yo|ig|en`, the audio as the request body, a Bearer "vv_..." key)
and the words are then translated to English with VoiceVerse's `/v1/translate`, so the assistant's commands and the
AI can work from English while the person speaks their own language. Nothing is stored.
"""

import logging
from dataclasses import dataclass
from typing import Protocol

import httpx

log = logging.getLogger("visionmirror")

TRANSCRIBED_LANGUAGES = frozenset({"ha", "yo", "ig"})


@dataclass(frozen=True)
class Transcription:
    text: str  # what was said, in the language it was said in
    english: str  # the same in English (equal to text when the language is English or translating failed)


def transcription_language(language: str) -> str | None:
    """'ha', 'ha-NG' -> 'ha'. None when VoiceVerse speech recognition is not used for this language."""
    base = language.split("-")[0].lower()
    return base if base in TRANSCRIBED_LANGUAGES else None


class Transcriber(Protocol):
    async def transcribe(self, audio: bytes, content_type: str, language: str) -> Transcription | None:
        """None when the speech could not be recognised right now (never raises)."""


class NoTranscriber:
    async def transcribe(self, audio: bytes, content_type: str, language: str) -> Transcription | None:
        return None


class VoiceVerseTranscriber:
    def __init__(self, base_url: str, api_key: str | None = None, timeout: float = 30.0,
                 client: httpx.AsyncClient | None = None):
        root = base_url.rstrip("/")
        self._stt = root + "/v1/transcribe"
        self._translate = root + "/v1/translate"
        self._auth = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.AsyncClient(timeout=timeout)

    async def transcribe(self, audio: bytes, content_type: str, language: str) -> Transcription | None:
        try:
            r = await self._client.post(
                self._stt, params={"language": language}, content=audio,
                headers={**self._auth, "Content-Type": content_type},
            )
            if r.status_code != 200:
                log.warning("transcription failed", extra={"ctx": {"status": r.status_code, "language": language}})
                return None
            text = str(r.json().get("text") or "").strip()
        except (httpx.HTTPError, ValueError) as e:
            log.warning("transcription error", extra={"ctx": {"type": type(e).__name__, "language": language}})
            return None
        if not text:
            return Transcription("", "")
        return Transcription(text, await self._to_english(text, language))

    async def _to_english(self, text: str, language: str) -> str:
        if language == "en":
            return text
        try:
            r = await self._client.post(
                self._translate, json={"text": text[:900], "source": language, "target": "en"}, headers=self._auth,
            )
            if r.status_code == 200:
                data = r.json()
                out = data.get("text")
                if data.get("translated") is True and isinstance(out, str) and out.strip():
                    return out.strip()
        except (httpx.HTTPError, ValueError):
            pass
        return text  # still better than nothing: the AI can read Yoruba, Hausa and Igbo too

    async def aclose(self) -> None:
        await self._client.aclose()
