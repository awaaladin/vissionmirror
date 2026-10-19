import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.main import create_app
from app.services.transcription_service import Transcription, VoiceVerseTranscriber, transcription_language
from tests.conftest import get_token

WAV = b"RIFF" + b"\x00" * 1200


class FakeTranscriber:
    async def transcribe(self, audio, content_type, language):
        return Transcription(f"{language}:{len(audio)}", "hello") if language != "xx" else None


def make(settings, redis, transcriber=None):
    app = create_app(settings=settings, redis=redis, provider=MockProvider("outfit"), transcriber=transcriber)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def test_language_codes():
    assert transcription_language("yo-NG") == "yo" and transcription_language("ha") == "ha"
    assert transcription_language("en-NG") is None and transcription_language("ibb") is None


@pytest.mark.asyncio
async def test_endpoint_returns_words_and_english(settings, redis):
    async with make(settings, redis, FakeTranscriber()) as c:
        h = await get_token(c)
        r = await c.post("/v1/transcribe?language=yo", headers={**h, "Content-Type": "audio/wav"}, content=WAV)
        assert r.status_code == 200 and r.json() == {"text": f"yo:{len(WAV)}", "english": "hello"}


@pytest.mark.asyncio
async def test_endpoint_checks_the_request(settings, redis):
    async with make(settings, redis, FakeTranscriber()) as c:
        assert (await c.post("/v1/transcribe", content=WAV, headers={"Content-Type": "audio/wav"})).status_code == 401
        h = await get_token(c)
        assert (await c.post("/v1/transcribe", headers={**h, "Content-Type": "text/plain"}, content=WAV)).status_code == 415
        assert (await c.post("/v1/transcribe", headers={**h, "Content-Type": "audio/wav"}, content=b"tiny")).status_code == 422
        r = await c.post("/v1/transcribe?language=xx", headers={**h, "Content-Type": "audio/wav"}, content=WAV)
        assert r.status_code == 501 and r.json()["code"] == "transcription_unavailable"


@pytest.mark.asyncio
async def test_not_configured_is_unavailable_not_an_error(settings, redis):
    async with make(settings, redis) as c:
        h = await get_token(c)
        r = await c.post("/v1/transcribe?language=ha", headers={**h, "Content-Type": "audio/wav"}, content=WAV)
        assert r.status_code == 501


@pytest.mark.asyncio
async def test_voiceverse_transcriber_transcribes_then_translates():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, request.url.params.get("language"), request.headers["authorization"]))
        if request.url.path == "/v1/transcribe":
            return httpx.Response(200, json={"text": "bawo ni"})
        return httpx.Response(200, json={"text": "how are you", "translated": True})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    t = VoiceVerseTranscriber("https://vv.example", "vv_key", client=client)
    got = await t.transcribe(WAV, "audio/wav", "yo")
    assert got == Transcription("bawo ni", "how are you")
    assert seen[0] == ("/v1/transcribe", "yo", "Bearer vv_key") and seen[1][0] == "/v1/translate"


@pytest.mark.asyncio
async def test_voiceverse_transcriber_fails_safely():
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(503)))
    assert await VoiceVerseTranscriber("https://vv.example", "k", client=client).transcribe(WAV, "audio/wav", "ig") is None
    boom = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: (_ for _ in ()).throw(httpx.ConnectError("x"))))
    assert await VoiceVerseTranscriber("https://vv.example", "k", client=boom).transcribe(WAV, "audio/wav", "ha") is None
