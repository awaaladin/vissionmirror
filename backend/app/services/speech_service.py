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
    async def synthesize(self, text: str, rate: float = 1.0) -> bytes: ...


class NoSpeech:
    async def synthesize(self, text: str, rate: float = 1.0) -> bytes:
        raise SpeechUnavailable("not configured")


def build_ssml(text: str, voice: str, rate: float) -> str:
    percent = round((max(0.5, min(rate, 2.0)) - 1.0) * 100)
    return (
        "<speak version='1.0' xml:lang='en-NG' xmlns='http://www.w3.org/2001/10/synthesis'>"
        f"<voice xml:lang='en-NG' name='{voice}'><prosody rate='{percent:+d}%'>{escape(text)}</prosody></voice></speak>"
    )


class AzureSpeech:
    def __init__(self, key: str, region: str, voice: str = "en-NG-EzinneNeural", timeout: float = 15.0,
                 client: httpx.AsyncClient | None = None):
        self._url = f"https://{region}.tts.speech.microsoft.com/cognitiveservices/v1"
        self._headers = {
            "Ocp-Apim-Subscription-Key": key,
            "Content-Type": "application/ssml+xml",
            "X-Microsoft-OutputFormat": AUDIO_FORMAT,
            "User-Agent": "visionmirror",
        }
        self._voice = voice
        self._client = client or httpx.AsyncClient(timeout=timeout)

    async def synthesize(self, text: str, rate: float = 1.0) -> bytes:
        body = build_ssml(text, self._voice, rate).encode("utf-8")
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
