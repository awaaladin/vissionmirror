"""Cloud voice: Nigerian English speech from Azure neural text-to-speech.

The phone's own voice is the fallback; this gives a natural Nigerian voice (en-NG Ezinne or Abeo) on any phone.
The Azure key stays on the server. The app asks for audio, plays it, and speaks locally if this is unavailable.
"""

import logging
from typing import Protocol
from xml.sax.saxutils import escape

import httpx

log = logging.getLogger("visionmirror")

AUDIO_FORMAT = "audio-24khz-48kbitrate-mono-mp3"


class SpeechUnavailable(Exception):
    """No key configured, or Azure failed. The app falls back to the phone's voice."""


class SpeechSynthesizer(Protocol):
    async def synthesize(self, text: str, rate: float = 1.0, voice: str = "female", language: str = "en") -> bytes: ...


class NoSpeech:
    async def synthesize(self, text: str, rate: float = 1.0, voice: str = "female", language: str = "en") -> bytes:
        raise SpeechUnavailable("not configured")


def build_ssml(text: str, voice: str, rate: float) -> str:
    percent = round((max(0.5, min(rate, 2.0)) - 1.0) * 100)
    return (
        "<speak version='1.0' xml:lang='en-NG' xmlns='http://www.w3.org/2001/10/synthesis'>"
        f"<voice xml:lang='en-NG' name='{voice}'><prosody rate='{percent:+d}%'>{escape(text)}</prosody></voice></speak>"
    )


class AzureSpeech:
    def __init__(self, key: str, region: str, voice: str = "en-NG-EzinneNeural", male_voice: str = "en-NG-AbeoNeural",
                 timeout: float = 15.0, client: httpx.AsyncClient | None = None):
        self._url = f"https://{region}.tts.speech.microsoft.com/cognitiveservices/v1"
        self._headers = {
            "Ocp-Apim-Subscription-Key": key,
            "Content-Type": "application/ssml+xml",
            "X-Microsoft-OutputFormat": AUDIO_FORMAT,
            "User-Agent": "visionmirror",
        }
        self._voices = {"female": voice, "male": male_voice}
        self._client = client or httpx.AsyncClient(timeout=timeout)

    async def synthesize(self, text: str, rate: float = 1.0, voice: str = "female", language: str = "en") -> bytes:
        body = build_ssml(text, self._voices.get(voice, self._voices["female"]), rate).encode("utf-8")
        for attempt in range(2):
            try:
                r = await self._client.post(self._url, content=body, headers=self._headers)
            except httpx.HTTPError as e:
                if attempt == 1:
                    log.warning("azure speech error", extra={"ctx": {"type": type(e).__name__}})
                    raise SpeechUnavailable from e
                continue
            if r.status_code in (429, 500, 502, 503, 504) and attempt == 0:
                continue
            if r.status_code != 200 or not r.content:
                log.warning("azure speech failed", extra={"ctx": {"status": r.status_code}})
                raise SpeechUnavailable
            return r.content
        raise SpeechUnavailable

    async def aclose(self) -> None:
        await self._client.aclose()


# Languages whose pronunciation lives in the VoiceVerse API. Phones and Azure have no voice for them: plain text is
# spelled out letter by letter. VoiceVerse turns the spelling into exact sounds (and Yoruba tone marks into pitch)
# and speaks it with a Nigerian voice. VisionMirror does not carry any of those rules.
VOICEVERSE_LANGUAGES = frozenset({"ha", "yo", "ig"})
VOICEVERSE_MAX_CHARS = 900  # VoiceVerse accepts 1000 per call


def speech_language(language: str) -> str:
    """'ha-NG' -> 'ha'."""
    return language.split("-")[0].lower()


class VoiceVerseSpeech:
    """Spoken audio from the VoiceVerse API: `POST /v1/speech` with a "vv_..." key as a Bearer token."""

    def __init__(self, base_url: str, api_key: str | None = None, timeout: float = 20.0,
                 client: httpx.AsyncClient | None = None):
        self._url = base_url.rstrip("/") + "/v1/speech"
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.AsyncClient(timeout=timeout)

    async def synthesize(self, text: str, rate: float = 1.0, voice: str = "female", language: str = "ha") -> bytes:
        # Long descriptions go in sentence-sized pieces; MP3 frames from one voice can simply be joined.
        from app.services.translation_service import chunk_sentences

        parts = [await self._one(chunk, rate, voice, speech_language(language))
                 for chunk in chunk_sentences(text, VOICEVERSE_MAX_CHARS)]
        if not parts:
            raise SpeechUnavailable("empty")
        return b"".join(parts)

    async def _one(self, text: str, rate: float, voice: str, language: str) -> bytes:
        body = {"text": text, "language": language, "voice": voice, "rate": rate}
        for attempt in range(2):
            try:
                r = await self._client.post(self._url, json=body, headers=self._headers)
            except httpx.HTTPError as e:
                if attempt == 1:
                    log.warning("voiceverse speech error", extra={"ctx": {"type": type(e).__name__}})
                    raise SpeechUnavailable from e
                continue
            if r.status_code in (429, 502, 503, 504) and attempt == 0:
                continue
            if r.status_code != 200 or not r.content:
                log.warning("voiceverse speech failed", extra={"ctx": {"status": r.status_code, "language": language}})
                raise SpeechUnavailable
            return r.content
        raise SpeechUnavailable

    async def aclose(self) -> None:
        await self._client.aclose()


class RoutingSpeech:
    """Hausa, Yoruba and Igbo go to VoiceVerse; everything else keeps the local (Azure Nigerian English) voice.

    If VoiceVerse cannot answer, the local voice is used, so she always hears something.
    """

    def __init__(self, local: SpeechSynthesizer, voiceverse: VoiceVerseSpeech):
        self._local = local
        self._voiceverse = voiceverse

    async def synthesize(self, text: str, rate: float = 1.0, voice: str = "female", language: str = "en") -> bytes:
        if speech_language(language) in VOICEVERSE_LANGUAGES:
            try:
                return await self._voiceverse.synthesize(text, rate, voice, language)
            except SpeechUnavailable:
                pass
        return await self._local.synthesize(text, rate, voice, language)

    async def aclose(self) -> None:
        await self._voiceverse.aclose()
        if hasattr(self._local, "aclose"):
            await self._local.aclose()
