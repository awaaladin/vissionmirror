import hashlib

from app.ai.provider import Turn, VisionProvider
from app.schemas.ask import AskResponse
from app.schemas.common import DetailLevel
from app.schemas.describe import (
    ColourHarmony,
    DescribeAnalysis,
    ImageQuality,
    Issue,
    OutfitItem,
)

SCENARIOS = ("outfit", "stain", "clash", "dark")

# How many of the importance-ordered sentences each detail level reads aloud.
_SENTENCES = {"brief": 2, "standard": 4, "detailed": 99}

_REFUSAL = "I don't judge looks, but I can tell you what I see and what could be adjusted."
_JUDGING_WORDS = (
    "attractive", "pretty", "beautiful", "ugly", "handsome", "sexy",
    "weight", "fat", "thin", "skinny", "skin", "how old", "my age",
)


def _spoken(sentences: list[str], level: DetailLevel) -> str:
    return " ".join(sentences[: _SENTENCES[level]])


def _outfit(level: DetailLevel) -> DescribeAnalysis:
    return DescribeAnalysis(
        image_quality=ImageQuality(usable=True),
        summary="You look put together, with a navy blue shirt and dark trousers that sit neatly.",
        outfit=[
            OutfitItem(item="top", description="Collared button-up shirt", color="navy blue", pattern="plain"),
            OutfitItem(item="bottom", description="Straight-leg trousers", color="charcoal grey", pattern="plain"),
        ],
        hair_and_grooming="Hair is short and neatly combed.",
        accessories=["a silver watch on the left wrist"],
        issues=[],
        colour_harmony=ColourHarmony(
            verdict="good", explanation="Navy blue and charcoal grey sit well together."
        ),
        spoken_text=_spoken(
            [
                "You look put together.",
                "You're wearing a navy blue collared shirt with charcoal grey trousers.",
                "The colours work well together.",
                "Your hair is short and neatly combed.",
                "I can see a silver watch on your left wrist.",
                "I don't see anything that needs fixing.",
            ],
            level,
        ),
        confidence=0.9,
    )


def _stain(level: DetailLevel) -> DescribeAnalysis:
    return DescribeAnalysis(
        image_quality=ImageQuality(usable=True),
        summary="Your white top looks fresh, but there may be a mark near the collar.",
        outfit=[
            OutfitItem(item="top", description="Short-sleeved blouse", color="white", pattern="plain"),
            OutfitItem(item="bottom", description="Wide-leg trousers", color="black", pattern="plain"),
        ],
        hair_and_grooming="Hair is tied back in a low ponytail.",
        accessories=[],
        issues=[
            Issue(
                severity="medium",
                what="A small dark mark that could be a stain",
                where="left side of the collar",
                suggestion="Dab it with a damp cloth, or switch to another top. I can't be sure it isn't a shadow.",
            ),
            Issue(
                severity="low",
                what="A light crease",
                where="across the front of the blouse",
                suggestion="A quick smooth with your hands or a steamer would help.",
            ),
        ],
        colour_harmony=ColourHarmony(verdict="good", explanation="White and black is a classic pairing."),
        spoken_text=_spoken(
            [
                "Your white blouse and black wide-leg trousers look crisp together.",
                "There is a small dark mark on the left side of the collar. I can't be sure whether that's a stain or a shadow, so it's worth a quick check.",
                "If it is a stain, dab it with a damp cloth or choose another top.",
                "There's also a light crease across the front of the blouse.",
                "Your hair is tied back in a low ponytail.",
            ],
            level,
        ),
        confidence=0.7,
    )


def _clash(level: DetailLevel) -> DescribeAnalysis:
    return DescribeAnalysis(
        image_quality=ImageQuality(usable=True),
        summary="Bold colours today. The top and skirt are competing a little.",
        outfit=[
            OutfitItem(item="top", description="Loose blouse", color="bright orange", pattern="floral"),
            OutfitItem(item="bottom", description="Skirt", color="hot pink", pattern="plain"),
            OutfitItem(item="shoes", description="Flat sandals", color="olive green", pattern=None),
        ],
        hair_and_grooming="Hair is worn down and looks smooth.",
        accessories=["gold hoop earrings"],
        issues=[
            Issue(
                severity="medium",
                what="Bright orange and hot pink are very close in intensity and may clash",
                where="top and skirt",
                suggestion="Try a neutral skirt such as cream or navy blue with this top.",
            )
        ],
        colour_harmony=ColourHarmony(
            verdict="clashing", explanation="Orange and hot pink compete rather than complement."
        ),
        spoken_text=_spoken(
            [
                "I love the energy in this outfit.",
                "The bright orange floral top and the hot pink skirt are fighting for attention, so the colours clash a bit.",
                "A cream or navy blue skirt would let the top stand out.",
                "Your olive green sandals are a calm choice.",
                "Your hair looks smooth and you're wearing gold hoop earrings.",
            ],
            level,
        ),
        confidence=0.8,
    )


def _dark() -> DescribeAnalysis:
    advice = "It's quite dark, try facing a window or turning on a light."
    return DescribeAnalysis(
        image_quality=ImageQuality(usable=False, issue="too_dark", advice=advice),
        summary="",
        colour_harmony=ColourHarmony(verdict="unsure", explanation=""),
        spoken_text=advice,
        confidence=0.0,
    )


_BUILDERS = {"outfit": _outfit, "stain": _stain, "clash": _clash}


class MockProvider(VisionProvider):
    """Canned output so the app can be built and demoed without API calls.

    The scenario comes from the constructor (MOCK_SCENARIO) or, if unset, from a hash of the
    image bytes, so the same photo always gets the same answer.
    """

    def __init__(self, scenario: str | None = None):
        if scenario is not None and scenario not in SCENARIOS:
            raise ValueError(f"Unknown mock scenario: {scenario}")
        self.scenario = scenario

    def _pick(self, image: bytes) -> str:
        if self.scenario:
            return self.scenario
        return SCENARIOS[hashlib.sha256(image).digest()[0] % len(SCENARIOS)]

    async def describe(self, *, image, media_type, detail_level, language) -> DescribeAnalysis:
        name = self._pick(image)
        return _dark() if name == "dark" else _BUILDERS[name](detail_level)

    async def ask(
        self, *, image, media_type, analysis, history: list[Turn], question, language
    ) -> AskResponse:
        q = question.lower()
        if any(w in q for w in _JUDGING_WORDS):
            return AskResponse(answer_text=_REFUSAL, spoken_text=_REFUSAL, confidence=1.0)
        if any(w in q for w in ("stain", "mark", "dirty", "wrong", "crease", "wrinkle")):
            if analysis.issues:
                text = " ".join(f"{i.what}, {i.where}. {i.suggestion}" for i in analysis.issues)
                return AskResponse(answer_text=text, spoken_text=text, confidence=0.7)
            text = "I don't see any marks or stains, though I can't be fully certain from one photo."
            return AskResponse(answer_text=text, spoken_text=text, confidence=0.6)
        if any(w in q for w in ("colour", "color", "match", "clash", "go together")):
            text = analysis.colour_harmony.explanation
            return AskResponse(answer_text=text, spoken_text=text, confidence=0.8)
        if "hair" in q:
            text = analysis.hair_and_grooming or "I can't tell much about your hair from this photo."
            return AskResponse(answer_text=text, spoken_text=text, confidence=0.8)
        text = f"{analysis.summary} You can ask me about the colours, your hair, or anything that looks off."
        return AskResponse(answer_text=text, spoken_text=text, confidence=0.6)
