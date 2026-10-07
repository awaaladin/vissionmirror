"""Shared logic for providers that talk to an LLM and need schema-valid JSON back."""

import base64
import logging
from abc import abstractmethod
from typing import TypeVar

from pydantic import BaseModel, ValidationError

from app.ai import prompts
from app.ai.provider import ProviderError, Turn, VisionProvider
from app.schemas.ask import AskResponse
from app.schemas.common import DetailLevel
from app.schemas.describe import ColourHarmony, DescribeAnalysis

log = logging.getLogger("visionmirror.ai")

T = TypeVar("T", bound=BaseModel)

ATTEMPTS = 2  # first try plus one retry on invalid JSON


def unavailable(message: str) -> ProviderError:
    return ProviderError(
        502,
        "ai_unavailable",
        message,
        "I'm having trouble looking at your photo right now. Please try again in a moment.",
    )


def image_b64(image: bytes) -> str:
    return base64.standard_b64encode(image).decode()


class StructuredProvider(VisionProvider):
    """Builds the conversation and validates the JSON; subclasses only do the HTTP call.

    Messages use a neutral shape: {"role": "user"|"assistant", "parts": [
    {"type": "image", "media_type", "data"(base64)} | {"type": "text", "text"}]}.
    """

    @abstractmethod
    async def _call(self, system: str, messages: list[dict], model_cls: type[BaseModel]) -> str:
        """Return the model's raw text reply (expected to be JSON for model_cls)."""

    async def describe(
        self, *, image: bytes, media_type: str, detail_level: DetailLevel, language: str
    ) -> DescribeAnalysis:
        result = await self._structured(
            system=prompts.describe_system_prompt(detail_level, language),
            messages=[
                {
                    "role": "user",
                    "parts": [_image_part(image, media_type), {"type": "text", "text": prompts.DESCRIBE_USER_TEXT}],
                }
            ],
            model_cls=DescribeAnalysis,
        )
        return normalize_analysis(result)

    async def ask(
        self,
        *,
        image: bytes,
        media_type: str,
        analysis: DescribeAnalysis,
        history: list[Turn],
        question: str,
        language: str,
    ) -> AskResponse:
        turns = [*(t.model_dump() for t in history), {"role": "user", "text": question}]
        messages: list[dict] = []
        for i, turn in enumerate(turns):
            parts: list[dict] = [{"type": "text", "text": turn["text"]}]
            if i == 0:  # the photo rides on the first user turn only
                parts.insert(0, _image_part(image, media_type))
            messages.append({"role": turn["role"], "parts": parts})
        return await self._structured(
            system=prompts.ask_system_prompt(analysis, language),
            messages=messages,
            model_cls=AskResponse,
        )

    async def _structured(self, *, system: str, messages: list[dict], model_cls: type[T]) -> T:
        messages = list(messages)
        last_error = ""
        for attempt in range(1, ATTEMPTS + 1):
            text = await self._call(system, messages, model_cls)
            try:
                return model_cls.model_validate_json(_strip_fences(text))
            except ValidationError as exc:
                # Log the failure kind only: never the model output, which describes a person.
                last_error = f"{exc.error_count()} validation errors"
                log.warning("invalid model output (attempt %d of %d): %s", attempt, ATTEMPTS, last_error)
                messages = [
                    *messages,
                    {"role": "assistant", "parts": [{"type": "text", "text": text}]},
                    {"role": "user", "parts": [{"type": "text", "text": prompts.RETRY_NOTE.format(error=last_error)}]},
                ]
        raise unavailable(f"Model returned invalid JSON after {ATTEMPTS} attempts ({last_error}).")


def _image_part(image: bytes, media_type: str) -> dict:
    return {"type": "image", "media_type": media_type, "data": image_b64(image)}


def _strip_fences(text: str) -> str:
    """Some models wrap JSON in ```json fences even when told not to."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else ""
        t = t.rsplit("```", 1)[0]
    return t.strip()


def normalize_analysis(a: DescribeAnalysis) -> DescribeAnalysis:
    """An unusable photo must never carry a description: don't guess, just give the advice."""
    if a.image_quality.usable:
        return a
    advice = a.image_quality.advice or "I can't see you clearly. Please try again in better light."
    return DescribeAnalysis(
        image_quality=a.image_quality.model_copy(update={"advice": advice}),
        summary="",
        outfit=[],
        hair_and_grooming="",
        accessories=[],
        issues=[],
        colour_harmony=ColourHarmony(verdict="unsure", explanation=""),
        spoken_text=advice,
        confidence=0.0,
    )
