import uuid

import pytest
from pydantic import ValidationError

from app.ai.mock_provider import SCENARIOS, MockProvider
from app.schemas.ask import AskRequest
from app.schemas.describe import DescribeAnalysis


def test_confidence_must_be_between_0_and_1():
    data = {"image_quality": {"usable": True}, "summary": "x", "spoken_text": "x",
            "colour_harmony": {"verdict": "good", "explanation": "x"}}
    with pytest.raises(ValidationError):
        DescribeAnalysis(**data, confidence=1.5)
    with pytest.raises(ValidationError):
        DescribeAnalysis(**data, confidence=-0.1)


def test_issue_severity_and_harmony_verdict_are_restricted():
    base = {"image_quality": {"usable": True}, "summary": "x", "spoken_text": "x", "confidence": 0.5}
    with pytest.raises(ValidationError):
        DescribeAnalysis(**base, colour_harmony={"verdict": "terrible", "explanation": "x"})
    with pytest.raises(ValidationError):
        DescribeAnalysis(
            **base,
            colour_harmony={"verdict": "good", "explanation": "x"},
            issues=[{"severity": "critical", "what": "a", "where": "b", "suggestion": "c"}],
        )


@pytest.mark.parametrize("scenario", SCENARIOS)
@pytest.mark.parametrize("level", ["brief", "standard", "detailed"])
async def test_every_mock_scenario_is_valid(scenario, level):
    result = await MockProvider(scenario).describe(
        image=b"x", media_type="image/jpeg", detail_level=level, language="en"
    )
    DescribeAnalysis.model_validate(result.model_dump())
    assert result.spoken_text


async def test_detail_level_scales_spoken_text():
    p = MockProvider("stain")
    lengths = [
        len((await p.describe(image=b"x", media_type="image/jpeg", detail_level=lvl, language="en")).spoken_text)
        for lvl in ("brief", "standard", "detailed")
    ]
    assert lengths[0] < lengths[1] < lengths[2]


async def test_dark_scenario_is_unusable_with_spoken_advice():
    r = await MockProvider("dark").describe(
        image=b"x", media_type="image/jpeg", detail_level="standard", language="en"
    )
    assert r.image_quality.usable is False
    assert "dark" in r.image_quality.advice.lower()
    assert r.outfit == [] and r.issues == []


def test_unknown_mock_scenario_rejected():
    with pytest.raises(ValueError):
        MockProvider("nope")


def test_ask_request_validation():
    sid = uuid.uuid4()
    assert AskRequest(session_id=sid, question="Is it clean?").language == "en"
    with pytest.raises(ValidationError):
        AskRequest(session_id=sid, question="")
    with pytest.raises(ValidationError):
        AskRequest(session_id=sid, question="x" * 501)
    with pytest.raises(ValidationError):
        AskRequest(session_id="not-a-uuid", question="hi")
    with pytest.raises(ValidationError):
        AskRequest(session_id=sid, question="hi", language="english!!")
