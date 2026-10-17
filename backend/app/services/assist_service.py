"""General help for Adanna: the weather, and answers to questions about the world.

Weather comes from Open-Meteo (free, no key). Questions are answered by the AI, given a short extract from Wikipedia
as fresh, checkable context when one is found. Where the person is comes from the place they name ("weather in
Abuja") or, failing that, the approximate location the hosting platform adds to the request (city level only).
Nothing here sees a photo.
"""

import logging
import re
from dataclasses import dataclass

import httpx

from app.ai.provider import VisionProvider
from app.core.rate_limit import DailyCap
from app.services.translation_service import FALLBACK_NOTICE, NoTranslator, Translator, translation_target

log = logging.getLogger("visionmirror")

GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
WIKI_SEARCH_URL = "https://en.wikipedia.org/w/rest.php/v1/search/page"
WIKI_SUMMARY_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/"

_WEATHER_WORDS = re.compile(
    r"\b(weather|temperature|forecast|rain(?:ing)?|raining|umbrella|hot outside|cold outside|how hot|how cold|sunny|humid)\b",
    re.IGNORECASE,
)
_PLACE = re.compile(
    r"\b(?:in|at|for|around)\s+([A-Za-z][A-Za-z .'-]{1,40}?)(?:\s+(?:today|tomorrow|tonight|now|right now|this \w+|please)\b|[?.!,]|$)",
    re.IGNORECASE,
)

# WMO weather codes, in words a person would say.
_CODES = {
    0: "clear", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "foggy", 48: "foggy",
    51: "drizzling", 53: "drizzling", 55: "drizzling", 56: "freezing drizzle", 57: "freezing drizzle",
    61: "raining lightly", 63: "raining", 65: "raining heavily", 66: "freezing rain", 67: "freezing rain",
    71: "snowing lightly", 73: "snowing", 75: "snowing heavily", 77: "snowing", 80: "showery", 81: "showery",
    82: "showery with heavy rain", 85: "snow showers", 86: "snow showers", 95: "thundery", 96: "thundery with hail",
    99: "thundery with hail",
}

NEED_CITY = "I can't tell where you are. Ask me like this: Adanna, what's the weather in Abuja?"
NO_ANSWER = "I couldn't look that up just now. Please try again in a moment."


@dataclass
class Where:
    name: str
    lat: float
    lon: float


def is_weather(query: str) -> bool:
    return bool(_WEATHER_WORDS.search(query))


def place_in(query: str) -> str | None:
    m = _PLACE.search(query)
    if not m:
        return None
    place = m.group(1).strip(" .'-")
    # "weather in the morning" is a time, not a place.
    return None if place.lower().split()[0] in {"the", "my", "a", "this", "that", "case", "general"} else place


def describe_weather(where: str, data: dict, tomorrow: bool) -> str:
    cur = data.get("current", {})
    day = data.get("daily", {})
    words = _CODES.get(int(cur.get("weather_code", -1)), "")

    def deg(v) -> str:
        return f"{round(v)} degrees"

    parts: list[str] = []
    i = 1 if tomorrow else 0
    try:
        hi, lo = day["temperature_2m_max"][i], day["temperature_2m_min"][i]
        rain = day.get("precipitation_probability_max", [None, None])[i]
    except (KeyError, IndexError, TypeError):
        hi = lo = rain = None
    if tomorrow and hi is not None:
        parts.append(f"Tomorrow in {where}, expect a high of {deg(hi)} and a low of {deg(lo)}.")
    elif "temperature_2m" in cur:
        now = f"Right now in {where} it is {deg(cur['temperature_2m'])}"
        if words:
            now += f" and {words}"
        parts.append(now + ".")
        feels = cur.get("apparent_temperature")
        if feels is not None and abs(feels - cur["temperature_2m"]) >= 3:
            parts.append(f"It feels like {deg(feels)}.")
        if hi is not None:
            parts.append(f"Today's high is {deg(hi)} and the low is {deg(lo)}.")
    if rain is not None:
        parts.append("It is unlikely to rain." if rain < 20 else f"There is a {round(rain)} percent chance of rain.")
    return " ".join(parts)


class AssistService:
    def __init__(
        self,
        provider: VisionProvider,
        cap: DailyCap,
        translator: Translator | None = None,
        client: httpx.AsyncClient | None = None,
    ):
        self.provider = provider
        self.cap = cap
        self.translator = translator or NoTranslator()
        self.client = client or httpx.AsyncClient(timeout=httpx.Timeout(10.0, connect=5.0))

    async def handle(self, *, query: str, language: str, here: Where | None) -> tuple[str, str]:
        """Returns (kind, spoken text). Never raises for a failed lookup: the text says so."""
        target = translation_target(language)
        if is_weather(query):
            kind, text = "weather", await self._weather(query, here)
        else:
            kind, text = "answer", await self._answer(query, "en" if target else language)
        if target:
            out = await self.translator.translate(text, target)
            text = out if out is not None else f"{FALLBACK_NOTICE} {text}"
        return kind, text

    # ---- weather -------------------------------------------------------------------------

    async def _weather(self, query: str, here: Where | None) -> str:
        where = None
        named = place_in(query)
        try:
            if named:
                where = await self._geocode(named)
                if where is None:
                    return f"I couldn't find a place called {named}."
            where = where or here
            if where is None:
                return NEED_CITY
            r = await self.client.get(
                FORECAST_URL,
                params={
                    "latitude": where.lat, "longitude": where.lon, "timezone": "auto", "forecast_days": 2,
                    "current": "temperature_2m,apparent_temperature,weather_code",
                    "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                },
            )
            r.raise_for_status()
            return describe_weather(where.name, r.json(), tomorrow="tomorrow" in query.lower()) or NO_ANSWER
        except (httpx.HTTPError, ValueError):
            log.warning("weather lookup failed")
            return NO_ANSWER

    async def _geocode(self, name: str) -> Where | None:
        r = await self.client.get(GEOCODE_URL, params={"name": name, "count": 1, "language": "en"})
        r.raise_for_status()
        hit = (r.json().get("results") or [None])[0]
        return Where(hit["name"], hit["latitude"], hit["longitude"]) if hit else None

    # ---- questions -----------------------------------------------------------------------

    async def _answer(self, query: str, language: str) -> str:
        await self.cap.reserve()
        try:
            context = await self._wikipedia(query)
            return await self.provider.answer(question=query, context=context, language=language)
        except Exception:
            await self.cap.release()
            raise

    async def _wikipedia(self, query: str) -> str:
        """A short extract for the best matching article, or "" (the AI then answers from what it knows)."""
        try:
            r = await self.client.get(WIKI_SEARCH_URL, params={"q": query, "limit": 1}, headers={"User-Agent": "visionmirror"})
            r.raise_for_status()
            pages = r.json().get("pages") or []
            if not pages:
                return ""
            s = await self.client.get(WIKI_SUMMARY_URL + pages[0]["key"], headers={"User-Agent": "visionmirror"})
            s.raise_for_status()
            data = s.json()
            return f"{data.get('title', '')}: {data.get('extract', '')}"[:1800]
        except (httpx.HTTPError, ValueError, KeyError):
            return ""
