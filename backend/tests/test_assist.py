import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.core.rate_limit import DailyCap
from app.main import create_app
from app.services.assist_service import AssistService, Where, describe_weather, is_weather, place_in
from tests.conftest import get_token


def test_weather_and_place_detection():
    assert is_weather("what's the weather in Abuja") and is_weather("will it rain tomorrow")
    assert not is_weather("who is the president of Ghana")
    assert place_in("what's the weather in Abuja today") == "Abuja"
    assert place_in("weather in Port Harcourt") == "Port Harcourt"
    assert place_in("what's the weather like in the morning") is None
    assert place_in("is it hot") is None


def test_weather_sentence():
    data = {
        "current": {"temperature_2m": 29.4, "apparent_temperature": 34.0, "weather_code": 2},
        "daily": {"temperature_2m_max": [31.2, 30.0], "temperature_2m_min": [24.6, 25.0], "precipitation_probability_max": [40, 10]},
    }
    today = describe_weather("Lagos", data, tomorrow=False)
    assert "Right now in Lagos it is 29 degrees and partly cloudy" in today
    assert "feels like 34 degrees" in today and "40 percent chance of rain" in today
    assert "Tomorrow in Lagos" in describe_weather("Lagos", data, tomorrow=True)


def handler(request: httpx.Request):
    host = request.url.host
    if host == "geocoding-api.open-meteo.com":
        return httpx.Response(200, json={"results": [{"name": "Abuja", "latitude": 9.07, "longitude": 7.4}]})
    if host == "api.open-meteo.com":
        return httpx.Response(200, json={
            "current": {"temperature_2m": 31, "apparent_temperature": 31, "weather_code": 0},
            "daily": {"temperature_2m_max": [33, 32], "temperature_2m_min": [23, 23], "precipitation_probability_max": [5, 5]},
        })
    if "wikipedia" in host and "search" in str(request.url):
        return httpx.Response(200, json={"pages": [{"key": "Lagos"}]})
    if "wikipedia" in host:
        return httpx.Response(200, json={"title": "Lagos", "extract": "Lagos is a city in Nigeria."})
    return httpx.Response(404)


def make(settings, redis, assist=None):
    cap = DailyCap(redis, 100)
    svc = assist or AssistService(MockProvider("outfit"), cap, client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    app = create_app(settings=settings, redis=redis, provider=MockProvider("outfit"), assist_service=svc)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


@pytest.mark.asyncio
async def test_weather_for_a_named_place(settings, redis):
    async with make(settings, redis) as c:
        h = await get_token(c)
        r = await c.post("/v1/assist", headers=h, json={"query": "what's the weather in Abuja"})
        assert r.status_code == 200 and r.json()["kind"] == "weather"
        assert "Abuja" in r.json()["spoken_text"] and "31 degrees" in r.json()["spoken_text"]


@pytest.mark.asyncio
async def test_weather_uses_platform_location_headers(settings, redis):
    async with make(settings, redis) as c:
        h = await get_token(c)
        h = {**h, "x-vercel-ip-city": "Lagos", "x-vercel-ip-latitude": "6.5", "x-vercel-ip-longitude": "3.4"}
        r = await c.post("/v1/assist", headers=h, json={"query": "is it going to rain"})
        assert "Lagos" in r.json()["spoken_text"]


@pytest.mark.asyncio
async def test_weather_with_no_place_asks_for_one(settings, redis):
    async with make(settings, redis) as c:
        h = await get_token(c)
        r = await c.post("/v1/assist", headers=h, json={"query": "what is the weather"})
        assert "weather in Abuja" in r.json()["spoken_text"]


@pytest.mark.asyncio
async def test_questions_are_answered_with_wikipedia_context(settings, redis):
    async with make(settings, redis) as c:
        h = await get_token(c)
        r = await c.post("/v1/assist", headers=h, json={"query": "tell me about Lagos"})
        assert r.json()["kind"] == "answer" and "Lagos" in r.json()["spoken_text"]


@pytest.mark.asyncio
async def test_a_failing_lookup_is_spoken_not_raised(settings, redis):
    broken = AssistService(
        MockProvider("outfit"), DailyCap(redis, 100),
        client=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(500))),
    )
    async with make(settings, redis, broken) as c:
        h = await get_token(c)
        r = await c.post("/v1/assist", headers=h, json={"query": "weather in Kano"})
        assert r.status_code == 200 and "couldn't" in r.json()["spoken_text"]
