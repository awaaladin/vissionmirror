import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.main import create_app
from app.services.speech_service import AzureSpeech, SpeechUnavailable, build_ssml
from tests.conftest import get_token


class FakeSpeech:
    async def synthesize(self, text, rate=1.0, voice="female"):
        return f"ID3-{voice}:".encode() + text.encode()


def make(settings, redis, speech=None):
    app = create_app(settings=settings, redis=redis, provider=MockProvider("outfit"), speech_service=speech)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def test_ssml_escapes_and_sets_rate():
    s = build_ssml("Tom & <Jerry>", "en-NG-EzinneNeural", 1.3)
    assert "Tom &amp; &lt;Jerry&gt;" in s and "rate='+30%'" in s and "en-NG-EzinneNeural" in s


@pytest.mark.asyncio
async def test_speech_endpoint_returns_audio(settings, redis):
    async with make(settings, redis, FakeSpeech()) as c:
        h = await get_token(c)
        r = await c.post("/v1/speech", headers=h, json={"text": "Well done"})
        assert r.status_code == 200 and r.headers["content-type"] == "audio/mpeg"
        assert r.content == b"ID3-female:Well done"
        m = await c.post("/v1/speech", headers=h, json={"text": "Well done", "voice": "male"})
        assert m.content == b"ID3-male:Well done"
        assert (await c.post("/v1/speech", headers=h, json={"text": "x", "voice": "robot"})).status_code == 422


@pytest.mark.asyncio
async def test_speech_requires_token_and_reports_unavailable(settings, redis):
    async with make(settings, redis) as c:
        assert (await c.post("/v1/speech", json={"text": "hi"})).status_code == 401
        h = await get_token(c)
        r = await c.post("/v1/speech", headers=h, json={"text": "hi"})
        assert r.status_code == 501 and r.json()["code"] == "speech_unavailable"


@pytest.mark.asyncio
async def test_azure_client_sends_key_and_ssml():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, content=b"mp3")

    a = AzureSpeech("k", "westeurope", client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    assert await a.synthesize("Hello") == b"mp3"
    assert seen[0].headers["ocp-apim-subscription-key"] == "k"
    assert str(seen[0].url) == "https://westeurope.tts.speech.microsoft.com/cognitiveservices/v1"

    bad = AzureSpeech("k", "westeurope", client=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(401))))
    with pytest.raises(SpeechUnavailable):
        await bad.synthesize("Hello")


@pytest.mark.asyncio
async def test_bola_uses_the_male_voice():
    seen = []

    def handler(request):
        seen.append(request.content.decode())
        return httpx.Response(200, content=b"mp3")

    a = AzureSpeech("k", "westeurope", client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    await a.synthesize("Hello", voice="female")
    await a.synthesize("Hello", voice="male")
    assert "en-NG-EzinneNeural" in seen[0] and "en-NG-AbeoNeural" in seen[1]
