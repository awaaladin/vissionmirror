import httpx
import pytest

from app.ai.gemini_provider import GeminiProvider
from app.ai.mock_provider import MockProvider
from app.ai import prompts
from app.main import create_app
from tests.conftest import JPEG, get_token


def make(settings, redis):
    app = create_app(settings=settings, redis=redis, provider=MockProvider("outfit"))
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


@pytest.mark.asyncio
async def test_read_returns_spoken_text(settings, redis):
    async with make(settings, redis) as c:
        h = await get_token(c)
        r = await c.post("/v1/read", headers=h, files={"image": ("p.jpg", JPEG, "image/jpeg")}, data={"ocr_text": "NAFDAC REG NO A4-1234"})
        assert r.status_code == 200
        assert "NAFDAC" in r.json()["spoken_text"]


@pytest.mark.asyncio
async def test_read_needs_a_token_and_an_image(settings, redis):
    async with make(settings, redis) as c:
        assert (await c.post("/v1/read", files={"image": ("p.jpg", JPEG, "image/jpeg")})).status_code == 401
        h = await get_token(c)
        assert (await c.post("/v1/read", headers=h)).status_code == 422


def test_the_reading_prompt_asks_for_sense_not_a_summary():
    p = prompts.read_system_prompt("en", "Bola")
    assert "Bola" in p and "abbreviation" in p.lower() and "cut off" in p and "Never add text that is not there" in p
    assert "NAFDAC" in p
    assert "phone's scanner" in prompts.read_user_text("HELLO WORLD")
    assert "scanner" not in prompts.read_user_text("")


@pytest.mark.asyncio
async def test_gemini_read_sends_the_photo_and_the_scanner_text():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = request.content.decode()
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": '{"spoken_text": "Paracetamol 500 milligrams."}'}]}, "finishReason": "STOP"}]})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    p = GeminiProvider(api_key="k", model="m", client=client)
    out = await p.read(image=JPEG, media_type="image/jpeg", ocr_text="PARACETAMOL 5OOmg", language="en")
    assert out == "Paracetamol 500 milligrams."
    assert "PARACETAMOL 5OOmg" in seen["body"] and "inline_data" in seen["body"].lower() or "inlineData" in seen["body"]
