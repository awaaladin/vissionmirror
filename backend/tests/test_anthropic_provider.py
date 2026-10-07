import base64
import json
from types import SimpleNamespace

import anthropic
import httpx
import pytest

from app.ai.anthropic_provider import ATTEMPTS, AnthropicProvider
from app.ai.mock_provider import MockProvider
from app.ai.provider import ProviderError, Turn, build_provider
from app.core.config import Settings

IMAGE = b"\xff\xd8\xff\xe0secret-pixels"


def reply(payload, stop_reason="end_turn"):
    text = payload if isinstance(payload, str) else json.dumps(payload)
    return SimpleNamespace(stop_reason=stop_reason, content=[SimpleNamespace(type="text", text=text)])


class FakeClient:
    """Stands in for AsyncAnthropic; replays queued replies (or raises queued exceptions)."""

    def __init__(self, *queue):
        self.queue = list(queue)
        self.calls: list[dict] = []
        self.messages = self

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        item = self.queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


async def good_describe(scenario="outfit"):
    result = await MockProvider(scenario).describe(
        image=b"x", media_type="image/jpeg", detail_level="standard", language="en"
    )
    return result.model_dump()


def provider(*queue):
    client = FakeClient(*queue)
    return AnthropicProvider(model="claude-opus-5-5", client=client), client


async def describe(p, level="standard"):
    return await p.describe(image=IMAGE, media_type="image/jpeg", detail_level=level, language="en")


async def test_describe_request_shape():
    p, client = provider(reply(await good_describe()))
    await describe(p, "brief")
    call = client.calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["output_config"]["format"]["type"] == "json_schema"
    assert call["output_config"]["effort"] == "medium"
    assert "tool_choice" not in call and "temperature" not in call and "thinking" not in call
    content = call["messages"][0]["content"]
    assert content[0]["type"] == "image"
    assert content[0]["source"] == {
        "type": "base64",
        "media_type": "image/jpeg",
        "data": base64.standard_b64encode(IMAGE).decode(),
    }
    assert "2 to 3 short sentences" in call["system"]


async def test_describe_parses_valid_json():
    p, client = provider(reply(await good_describe("clash")))
    result = await describe(p)
    assert result.colour_harmony.verdict == "clashing"
    assert len(client.calls) == 1


async def test_invalid_json_retries_once_then_succeeds():
    p, client = provider(reply("not json {"), reply(await good_describe()))
    result = await describe(p)
    assert result.image_quality.usable
    assert len(client.calls) == 2
    retry_messages = client.calls[1]["messages"]
    assert retry_messages[-2]["role"] == "assistant"
    assert "not valid JSON" in retry_messages[-1]["content"][0]["text"]


async def test_schema_violation_counts_as_invalid():
    bad = await good_describe()
    bad["confidence"] = 7
    p, client = provider(reply(bad), reply(await good_describe()))
    await describe(p)
    assert len(client.calls) == 2


async def test_gives_up_after_one_retry_with_speakable_502():
    p, client = provider(reply("nope"), reply("still nope"))
    with pytest.raises(ProviderError) as exc:
        await describe(p)
    assert len(client.calls) == ATTEMPTS == 2
    assert exc.value.status_code == 502
    assert exc.value.spoken_text
    assert "nope" not in exc.value.message  # model output must not leak into errors


async def test_unusable_photo_is_normalised_to_advice_only():
    sloppy = await good_describe("outfit")
    sloppy["image_quality"] = {"usable": False, "issue": "too_dark", "advice": "Face a window."}
    p, _ = provider(reply(sloppy))
    result = await describe(p)
    assert result.outfit == [] and result.issues == [] and result.summary == ""
    assert result.spoken_text == "Face a window."
    assert result.confidence == 0.0
    assert result.colour_harmony.verdict == "unsure"


async def test_unusable_without_advice_still_gets_spoken_advice():
    sloppy = await good_describe("dark")
    sloppy["image_quality"]["advice"] = None
    p, _ = provider(reply(sloppy))
    assert (await describe(p)).spoken_text


@pytest.mark.parametrize("stop", ["refusal", "max_tokens"])
async def test_refusal_and_truncation_become_502(stop):
    p, _ = provider(reply(await good_describe(), stop_reason=stop))
    with pytest.raises(ProviderError):
        await describe(p)


async def test_api_errors_become_502():
    req = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    err = anthropic.APIConnectionError(request=req)
    p, _ = provider(err)
    with pytest.raises(ProviderError) as exc:
        await describe(p)
    assert exc.value.code == "ai_unavailable"


async def test_ask_sends_photo_context_and_history():
    analysis = await MockProvider("stain").describe(
        image=b"x", media_type="image/jpeg", detail_level="standard", language="en"
    )
    p, client = provider(reply({"answer_text": "a", "spoken_text": "a", "confidence": 0.6}))
    out = await p.ask(
        image=IMAGE,
        media_type="image/jpeg",
        analysis=analysis,
        history=[Turn(role="user", text="Hair?"), Turn(role="assistant", text="Tied back.")],
        question="And the collar?",
        language="en",
    )
    assert out.spoken_text == "a"
    call = client.calls[0]
    roles = [m["role"] for m in call["messages"]]
    assert roles == ["user", "assistant", "user"]
    assert call["messages"][0]["content"][0]["type"] == "image"
    assert all(m["content"][0]["type"] == "text" for m in call["messages"][1:])
    assert call["messages"][-1]["content"][0]["text"] == "And the collar?"
    assert "left side of the collar" in call["system"]


async def test_ask_without_history_has_photo_and_question_together():
    analysis = await MockProvider("outfit").describe(
        image=b"x", media_type="image/png", detail_level="standard", language="en"
    )
    p, client = provider(reply({"answer_text": "a", "spoken_text": "a", "confidence": 1}))
    await p.ask(image=IMAGE, media_type="image/png", analysis=analysis, history=[], question="Hi?", language="en")
    content = client.calls[0]["messages"][0]["content"]
    assert [b["type"] for b in content] == ["image", "text"]


def test_build_provider_requires_key_for_anthropic():
    base = dict(secret_key="x" * 32, vision_provider="anthropic", _env_file=None)
    with pytest.raises(ValueError):
        build_provider(Settings(**base))
    built = build_provider(Settings(**base, anthropic_api_key="sk-test"))
    assert isinstance(built, AnthropicProvider)


def test_logs_never_contain_image_or_description(caplog):
    import asyncio

    p, _ = provider(reply("garbage with secret-pixels"), reply("garbage again"))
    with caplog.at_level("DEBUG"):
        with pytest.raises(ProviderError):
            asyncio.run(describe(p))
    assert "secret-pixels" not in caplog.text
    assert "garbage" not in caplog.text
