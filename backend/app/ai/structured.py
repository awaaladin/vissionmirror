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


class SpokenAnswer(BaseModel):
    spoken_text: str


ESCALATE_CONFIDENCE = 0.65
_REFUSALS = ("cant read", "cannot read", "no readable", "unable to read", "couldnt read", "i cant see any", "nothing to read")


def weak_describe(a: DescribeAnalysis) -> str | None:
    """Why the cheaper model's description is not good enough to give a blind person (None = it is fine)."""
    if not a.image_quality.usable:
        return "it called the photo unusable"
    if a.confidence < ESCALATE_CONFIDENCE:
        return f"confidence {a.confidence:.2f}"
    if len(a.spoken_text.split()) < 12:
        return "a very short answer"
    return None


def weak_read(ocr_text: str, spoken: str) -> str | None:
    scanned = ocr_text.split()
    said = spoken.lower().replace("'", "").replace("\u2019", "")
    if len(ocr_text.strip()) > 500:
        return "dense text"
    if len(scanned) >= 25 and len(spoken.split()) < 0.5 * len(scanned):
        return "it read much less than the scanner found"
    if any(r in said for r in _REFUSALS):
        return "it said it could not read"
    return None


class StructuredProvider(VisionProvider):
    """Builds the conversation and validates the JSON; subclasses only do the HTTP call.

    Messages use a neutral shape: {"role": "user"|"assistant", "parts": [
    {"type": "image", "media_type", "data"(base64)} | {"type": "text", "text"}]}.
    """

    @abstractmethod
    async def _call(self, system: str, messages: list[dict], model_cls: type[BaseModel]) -> str:
        """Return the model's raw text reply (expected to be JSON for model_cls)."""

    def stronger(self) -> "StructuredProvider | None":
        """A copy of this provider that uses the stronger model, or None when there is none to escalate to."""
        return None

    async def describe(
        self, *, image: bytes, media_type: str, detail_level: DetailLevel, language: str, mode: str = "mirror",
        assistant: str = "Adanna",
    ) -> DescribeAnalysis:
        analysis = await self._describe(image=image, media_type=media_type, detail_level=detail_level, language=language, mode=mode, assistant=assistant)
        reason = weak_describe(analysis)
        strong = self.stronger() if reason else None
        if strong is not None:
            try:
                better = await strong._describe(image=image, media_type=media_type, detail_level=detail_level, language=language, mode=mode, assistant=assistant)
            except ProviderError:
                return analysis  # the stronger model was busy: the first answer is still an answer
            log.info("describe escalated to the stronger model (%s)", reason)
            return better
        return analysis

    async def _describe(
        self, *, image: bytes, media_type: str, detail_level: DetailLevel, language: str, mode: str = "mirror",
        assistant: str = "Adanna",
    ) -> DescribeAnalysis:
        result = await self._structured(
            system=prompts.describe_system_prompt(detail_level, language, mode, assistant),
            messages=[
                {
                    "role": "user",
                    "parts": [_image_part(image, media_type), {"type": "text", "text": prompts.SCENE_USER_TEXT if mode == "scene" else prompts.DESCRIBE_USER_TEXT}],
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
        assistant: str = "Adanna",
    ) -> AskResponse:
        kwargs = dict(image=image, media_type=media_type, analysis=analysis, history=history, question=question, language=language, assistant=assistant)
        reply = await self._ask(**kwargs)
        strong = self.stronger() if reply.confidence < 0.6 else None
        if strong is not None:
            try:
                better = await strong._ask(**kwargs)
            except ProviderError:
                return reply
            log.info("ask escalated to the stronger model (confidence %.2f)", reply.confidence)
            return better
        return reply

    async def _ask(
        self,
        *,
        image: bytes,
        media_type: str,
        analysis: DescribeAnalysis,
        history: list[Turn],
        question: str,
        language: str,
        assistant: str = "Adanna",
    ) -> AskResponse:
        turns = [*(t.model_dump() for t in history), {"role": "user", "text": question}]
        messages: list[dict] = []
        for i, turn in enumerate(turns):
            parts: list[dict] = [{"type": "text", "text": turn["text"]}]
            if i == 0:  # the photo rides on the first user turn only
                parts.insert(0, _image_part(image, media_type))
            messages.append({"role": turn["role"], "parts": parts})
        return await self._structured(
            system=prompts.ask_system_prompt(analysis, language, assistant),
            messages=messages,
            model_cls=AskResponse,
        )

    async def read(self, *, image: bytes, media_type: str, ocr_text: str, language: str, assistant: str = "Adanna") -> str:
        text = await self._read(image=image, media_type=media_type, ocr_text=ocr_text, language=language, assistant=assistant)
        reason = weak_read(ocr_text, text)
        strong = self.stronger() if reason else None
        if strong is not None:
            try:
                better = await strong._read(image=image, media_type=media_type, ocr_text=ocr_text, language=language, assistant=assistant)
            except ProviderError:
                return text
            log.info("read escalated to the stronger model (%s)", reason)
            return better
        return text

    async def _read(self, *, image: bytes, media_type: str, ocr_text: str, language: str, assistant: str = "Adanna") -> str:
        result = await self._structured(
            system=prompts.read_system_prompt(language, assistant),
            messages=[{"role": "user", "parts": [_image_part(image, media_type), {"type": "text", "text": prompts.read_user_text(ocr_text)}]}],
            model_cls=SpokenAnswer,
        )
        return result.spoken_text.strip()

    async def answer(self, *, question: str, context: str, language: str, assistant: str = "Adanna") -> str:
        result = await self._structured(
            system=prompts.answer_system_prompt(language, assistant),
            messages=[{"role": "user", "parts": [{"type": "text", "text": prompts.answer_user_text(question, context)}]}],
            model_cls=SpokenAnswer,
        )
        return result.spoken_text.strip()

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
