import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.main import create_app
from app.services.translation_service import (
    FALLBACK_NOTICE,
    VoiceVerseTranslator,
    chunk_sentences,
    translation_target,
)
from tests.conftest import JPEG, get_token


class FakeTranslator:
    def __init__(self, result="ABC"):
        self.result = result
        self.calls = []

    async def translate(self, text, target):
        self.calls.append((text, target))
        return None if self.result is None else f"[{target}] {text}"


def client_with(settings, redis, translator):
    app = create_app(settings=settings, redis=redis, provider=MockProvider("outfit"), translator=translator)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def test_translation_target():
    assert translation_target("ha") == "ha"
    assert translation_target("YO-NG") == "yo"
    assert translation_target("ibb") == "ibb"
    assert translation_target("en-NG") is None
    assert translation_target("fr") is None


def test_chunking_keeps_sentences_and_limit():
    text = " ".join(f"Sentence number {i} is here." for i in range(100))
    chunks = chunk_sentences(text, limit=200)
    assert all(len(c) <= 200 for c in chunks)
    assert " ".join(chunks) == text
    assert len(chunk_sentences("x" * 2500, limit=900)) == 3


@pytest.mark.asyncio
async def test_describe_is_translated_and_session_keeps_english(settings, redis):
    tr = FakeTranslator()
    async with client_with(settings, redis, tr) as c:
        h = await get_token(c)
        r = await c.post("/v1/describe", headers=h, files={"image": ("p.jpg", JPEG, "image/jpeg")}, data={"language": "ig"})
        assert r.status_code == 200
        body = r.json()
        assert body["spoken_text"].startswith("[ig] ")
        assert tr.calls and all(t == "ig" for _, t in tr.calls)
        a = await c.post("/v1/ask", headers=h, json={"session_id": body["session_id"], "question": "Is it neat?", "language": "ig"})
        assert a.status_code == 200
        assert a.json()["spoken_text"].startswith("[ig] ")


@pytest.mark.asyncio
async def test_failed_translation_falls_back_to_english_with_notice(settings, redis):
    async with client_with(settings, redis, FakeTranslator(result=None)) as c:
        h = await get_token(c)
        r = await c.post("/v1/describe", headers=h, files={"image": ("p.jpg", JPEG, "image/jpeg")}, data={"language": "ha"})
        assert r.status_code == 200
        assert r.json()["spoken_text"].startswith(FALLBACK_NOTICE)


@pytest.mark.asyncio
async def test_english_never_touches_translator(settings, redis):
    tr = FakeTranslator()
    async with client_with(settings, redis, tr) as c:
        h = await get_token(c)
        r = await c.post("/v1/describe", headers=h, files={"image": ("p.jpg", JPEG, "image/jpeg")}, data={"language": "en-NG"})
        assert r.status_code == 200
        assert tr.calls == []


@pytest.mark.asyncio
async def test_voiceverse_client_contract_retry_and_cache():
    seen = []

    def handler(request: httpx.Request):
        seen.append(request)
        if len(seen) == 1:
            return httpx.Response(503)
        return httpx.Response(200, json={"text": "Ndi", "translated": True, "engine": "usem", "reason": None})

    t = VoiceVerseTranslator("https://vv.example/", "k", client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    assert await t.translate("Hello.", "ibb") == "Ndi"
    assert seen[0].url == "https://vv.example/v1/translate"
    assert seen[0].headers["authorization"] == "Bearer k"
    assert b'"target":"ibb"' in seen[0].content.replace(b" ", b"")
    assert len(seen) == 2  # retried once after the 503
    assert await t.translate("Hello.", "ibb") == "Ndi"
    assert len(seen) == 2  # cached


@pytest.mark.asyncio
async def test_voiceverse_untranslated_reply_is_a_failure():
    def handler(request):
        return httpx.Response(200, json={"text": "Hello.", "translated": False, "engine": "none", "reason": "no_provider"})

    t = VoiceVerseTranslator("https://vv.example", client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    assert await t.translate("Hello.", "yo") is None
