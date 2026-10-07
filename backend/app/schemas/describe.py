import uuid
from typing import Literal

from pydantic import BaseModel, Field


class ImageQuality(BaseModel):
    usable: bool
    issue: str | None = None
    advice: str | None = None


class OutfitItem(BaseModel):
    item: str
    description: str
    color: str | None = None
    pattern: str | None = None


class Issue(BaseModel):
    severity: Literal["low", "medium", "high"]
    what: str
    where: str
    suggestion: str


class ColourHarmony(BaseModel):
    verdict: Literal["good", "mixed", "clashing", "unsure"]
    explanation: str


class DescribeAnalysis(BaseModel):
    """What a VisionProvider returns; the API adds session_id."""

    image_quality: ImageQuality
    summary: str
    outfit: list[OutfitItem] = []
    hair_and_grooming: str = ""
    accessories: list[str] = []
    issues: list[Issue] = []
    colour_harmony: ColourHarmony
    spoken_text: str
    confidence: float = Field(ge=0.0, le=1.0)


class DescribeResponse(DescribeAnalysis):
    # null when image_quality.usable is false: nothing is stored, so there is nothing to ask about.
    session_id: uuid.UUID | None
