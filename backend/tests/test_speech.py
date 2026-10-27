import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.main import create_app
from app.services.speech_service import (
    AzureSpeech,
    RoutingSpeech,
    SpeechUnavailable,
    VoiceVerseSpeech,
    build_ssml,
)
from tests.conftest import get_token


class FakeSpeech:
    async def synthesize(self, text, rate=1.0, voice="female", language="en"):
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


# --- Hausa, Yoruba and Igbo are pronounced by the VoiceVerse API ------------------------------------------------------


class Recorder:
    """A speech backend that records what it was asked and returns a tagged audio stub."""

    def __init__(self, tag, fail=False):
        self.tag, self.fail, self.calls = tag, fail, []

    async def synthesize(self, text, rate=1.0, voice="female", language="en"):
        self.calls.append((text, rate, voice, language))
        if self.fail:
            raise SpeechUnavailable
        return f"{self.tag}:{language}:{text}".encode()


@pytest.mark.asyncio
async def test_endpoint_passes_the_language_through(settings, redis):
    seen = []

    class Spy:
        async def synthesize(self, text, rate=1.0, voice="female", language="en"):
            seen.append(language)
            return b"ID3"

    async with make(settings, redis, Spy()) as c:
        h = await get_token(c)
        await c.post("/v1/speech", headers=h, json={"text": "Barka", "language": "ha"})
        await c.post("/v1/speech", headers=h, json={"text": "Hello"})
        assert seen == ["ha", "en"]
        assert (await c.post("/v1/speech", headers=h, json={"text": "x", "language": "not a language"})).status_code == 422


@pytest.mark.asyncio
async def test_voiceverse_speech_sends_text_language_voice_and_key():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, content=b"ID3-from-voiceverse", headers={"content-type": "audio/mpeg"})

    vv = VoiceVerseSpeech("https://vv.test/", "vv_secret", client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    audio = await vv.synthesize("Barka da safiya", 1.2, "male", "ha-NG")
    assert audio == b"ID3-from-voiceverse"
    req = seen[0]
    assert str(req.url) == "https://vv.test/v1/speech"
    assert req.headers["authorization"] == "Bearer vv_secret"
    import json
    assert json.loads(req.content) == {"text": "Barka da safiya", "language": "ha", "voice": "male", "rate": 1.2}


@pytest.mark.asyncio
async def test_voiceverse_speech_splits_long_text_and_joins_the_audio():
    bodies = []

    def handler(request):
        import json
        bodies.append(json.loads(request.content)["text"])
        return httpx.Response(200, content=b"A")

    vv = VoiceVerseSpeech("https://vv.test", "k", client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    long_text = " ".join(["Wannan jumla ce mai tsawo sosai."] * 60)  # well over 900 characters
    audio = await vv.synthesize(long_text, language="ha")
    assert len(bodies) >= 2 and all(len(b) <= 900 for b in bodies)
    assert audio == b"A" * len(bodies)


@pytest.mark.asyncio
@pytest.mark.parametrize("failing", [401, 422, 500])
async def test_voiceverse_speech_failure_is_unavailable(failing):
    vv = VoiceVerseSpeech(
        "https://vv.test", "k",
        client=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(failing))),
    )
    with pytest.raises(SpeechUnavailable):
        await vv.synthesize("Barka", language="ha")


@pytest.mark.asyncio
async def test_routing_sends_hausa_yoruba_igbo_to_voiceverse_and_the_rest_to_the_local_voice():
    local, vv = Recorder("local"), Recorder("vv")
    router = RoutingSpeech(local, vv)
    for lang in ("ha", "yo", "ig", "ha-NG", "YO"):
        assert (await router.synthesize("text", 1.0, "female", lang)).startswith(b"vv:")
    for lang in ("en", "en-NG", "ibb", "fr"):
        assert (await router.synthesize("text", 1.0, "female", lang)).startswith(b"local:")
    assert [c[3] for c in vv.calls] == ["ha", "yo", "ig", "ha-NG", "YO"]


@pytest.mark.asyncio
async def test_routing_falls_back_to_the_local_voice_when_voiceverse_is_down():
    local, vv = Recorder("local"), Recorder("vv", fail=True)
    audio = await RoutingSpeech(local, vv).synthesize("Barka", 1.0, "female", "ha")
    assert audio == b"local:ha:Barka"
    assert len(vv.calls) == 1 and len(local.calls) == 1


@pytest.mark.asyncio
async def test_app_uses_voiceverse_when_configured(settings, redis):
    from app.core.config import Settings

    assert isinstance(settings, Settings)
    configured = settings.model_copy(update={"voiceverse_url": "https://vv.test", "voiceverse_api_key": "vv_x"})
    app = create_app(settings=configured, redis=redis, provider=MockProvider("outfit"))
    assert isinstance(app.state.speech, RoutingSpeech)
    plain = create_app(settings=settings, redis=redis, provider=MockProvider("outfit"))
    assert not isinstance(plain.state.speech, RoutingSpeech)


class CountingSpeech(FakeSpeech):
    def __init__(self):
        self.calls = 0

    async def synthesize(self, text, rate=1.0, voice="female", language="en"):
        self.calls += 1
        return await super().synthesize(text, rate, voice, language)


@pytest.mark.asyncio
async def test_local_language_voice_has_a_daily_share_and_repeats_are_free(settings, redis):
    settings.local_voice_daily_pieces = 2
    fake = CountingSpeech()
    async with make(settings, redis, fake) as c:
        h = await get_token(c)
        say = lambda t, lang="ha": c.post("/v1/speech", headers=h, json={"text": t, "language": lang})
        assert (await say("Sannu")).status_code == 200
        assert (await say("Sannu")).status_code == 200 and fake.calls == 1  # the same sentence is not bought twice
        assert (await say("Barka")).status_code == 200
        over = await say("Ina kwana")
        assert over.status_code == 501 and over.json()["code"] == "speech_unavailable"  # the app uses the phone's voice
        assert (await say("Sannu")).status_code == 200  # already bought, still free
        assert (await say("Good morning", "en")).status_code == 200  # English is not part of this share
