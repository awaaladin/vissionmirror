import json

import httpx
import pytest

from app.ai.gemini_provider import GeminiProvider
from app.ai.mock_provider import MockProvider
from app.ai.provider import ProviderError, Turn, build_provider
from app.core.config import Settings

IMAGE = b"\xff\xd8\xff\xe0secret-pixels"


def gemini_reply(text, finish="STOP", status=200, extra=None):
    body = {"candidates": [{"content": {"parts": [{"text": text}]}, "finishReason": finish}]}
    body.update(extra or {})
    return httpx.Response(status, json=body)


@pytest.fixture(autouse=True)
def no_backoff_sleep(monkeypatch):
    monkeypatch.setattr("app.ai.gemini_provider.RETRY_DELAYS", (0.0, 0.0))


def make(*responses):
    """GeminiProvider wired to a fake HTTP transport that replays `responses` and records requests.
    The last response repeats forever, so a failing one is retried the same way each time."""
    queue, seen = list(responses), []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        item = queue.pop(0) if len(queue) > 1 else queue[0]
        if isinstance(item, Exception):
            raise item
        return item

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return GeminiProvider(api_key="test-key", model="gemini-test", client=client), seen


async def good():
    r = await MockProvider("stain").describe(image=b"x", media_type="image/jpeg", detail_level="standard", language="en")
    return json.dumps(r.model_dump())


async def describe(p):
    return await p.describe(image=IMAGE, media_type="image/jpeg", detail_level="standard", language="en")


async def test_request_shape_and_key_in_header_not_url():
    p, seen = make(gemini_reply(await good()))
    result = await describe(p)
    assert result.issues[0].severity == "medium"
    req = seen[0]
    assert req.url.path.endswith("/models/gemini-test:generateContent")
    assert "test-key" not in str(req.url)
    assert req.headers["x-goog-api-key"] == "test-key"
    body = json.loads(req.content)
    assert body["generationConfig"]["responseMimeType"] == "application/json"
    assert body["generationConfig"]["thinkingConfig"] == {"thinkingLevel": "medium"}
    parts = body["contents"][0]["parts"]
    assert parts[0]["inline_data"]["mime_type"] == "image/jpeg"
    assert parts[1]["text"]
    assert "JSON schema" in body["systemInstruction"]["parts"][0]["text"]


async def test_tolerates_markdown_fenced_json():
    p, _ = make(gemini_reply("```json\n" + await good() + "\n```"))
    assert (await describe(p)).confidence == 0.7


async def test_invalid_json_retries_once():
    p, seen = make(gemini_reply("{oops"), gemini_reply(await good()))
    await describe(p)
    assert len(seen) == 2
    roles = [c["role"] for c in json.loads(seen[1].content)["contents"]]
    assert roles == ["user", "model", "user"]


async def test_two_failures_give_speakable_502():
    p, _ = make(gemini_reply("{oops"), gemini_reply("{still"))
    with pytest.raises(ProviderError) as exc:
        await describe(p)
    assert exc.value.status_code == 502 and exc.value.spoken_text


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(429, json={"error": {"message": "quota"}}),
        httpx.Response(500, text="boom"),
        gemini_reply("", finish="SAFETY"),
        gemini_reply("{}", finish="MAX_TOKENS"),
        httpx.Response(200, json={"promptFeedback": {"blockReason": "SAFETY"}}),
        httpx.Response(200, json={"candidates": []}),
    ],
)
async def test_failures_become_502(response):
    p, _ = make(response)
    with pytest.raises(ProviderError):
        await describe(p)


async def test_transient_503_is_retried_then_succeeds():
    p, seen = make(httpx.Response(503, text="busy"), httpx.Response(429, text="slow down"), gemini_reply(await good()))
    assert (await describe(p)).confidence == 0.7
    assert len(seen) == 3


async def test_persistent_503_gives_up_after_three_tries():
    p, seen = make(httpx.Response(503, text="busy"))
    with pytest.raises(ProviderError):
        await describe(p)
    assert len(seen) == 3


async def test_non_transient_error_is_not_retried():
    p, seen = make(httpx.Response(404, text="no such model"))
    with pytest.raises(ProviderError):
        await describe(p)
    assert len(seen) == 1


async def test_network_error_becomes_502():
    p, _ = make(httpx.ConnectError("down"))
    with pytest.raises(ProviderError):
        await describe(p)


async def test_unusable_photo_normalised():
    sloppy = json.loads(await good())
    sloppy["image_quality"] = {"usable": False, "issue": "too_dark", "advice": "Face a window."}
    p, _ = make(gemini_reply(json.dumps(sloppy)))
    r = await describe(p)
    assert r.outfit == [] and r.spoken_text == "Face a window."


async def test_ask_maps_assistant_turns_to_model_role():
    analysis = await MockProvider("outfit").describe(image=b"x", media_type="image/jpeg", detail_level="standard", language="en")
    p, seen = make(gemini_reply(json.dumps({"answer_text": "a", "spoken_text": "a", "confidence": 0.5})))
    await p.ask(
        image=IMAGE, media_type="image/jpeg", analysis=analysis,
        history=[Turn(role="user", text="Hair?"), Turn(role="assistant", text="Short.")],
        question="Shoes?", language="en",
    )
    contents = json.loads(seen[0].content)["contents"]
    assert [c["role"] for c in contents] == ["user", "model", "user"]
    assert "inline_data" in contents[0]["parts"][0] and "inline_data" not in json.dumps(contents[1:])


def test_build_provider_gemini_needs_key():
    base = dict(secret_key="x" * 32, vision_provider="gemini", _env_file=None)  # ignore the real .env
    with pytest.raises(ValueError):
        build_provider(Settings(**base))
    assert isinstance(build_provider(Settings(**base, gemini_api_key="k")), GeminiProvider)
