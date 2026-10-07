from abc import ABC, abstractmethod
from typing import Literal

from pydantic import BaseModel

from app.core.config import Settings
from app.core.errors import APIError
from app.schemas.ask import AskResponse
from app.schemas.common import DetailLevel
from app.schemas.describe import DescribeAnalysis


class Turn(BaseModel):
    role: Literal["user", "assistant"]
    text: str


class ProviderError(APIError):
    """The AI backend failed. Rendered by the API as a 502 with a speakable message."""


class VisionProvider(ABC):
    @abstractmethod
    async def describe(
        self, *, image: bytes, media_type: str, detail_level: DetailLevel, language: str
    ) -> DescribeAnalysis: ...

    @abstractmethod
    async def ask(
        self,
        *,
        image: bytes,
        media_type: str,
        analysis: DescribeAnalysis,
        history: list[Turn],
        question: str,
        language: str,
    ) -> AskResponse: ...


def build_provider(settings: Settings) -> VisionProvider:
    if settings.vision_provider == "mock":
        from app.ai.mock_provider import MockProvider

        return MockProvider(scenario=settings.mock_scenario or None)
    if settings.vision_provider == "gemini":
        if not settings.gemini_api_key:
            raise ValueError("GEMINI_API_KEY is required when VISION_PROVIDER=gemini")
        from app.ai.gemini_provider import GeminiProvider

        return GeminiProvider(
            api_key=settings.gemini_api_key,
            model=settings.gemini_model,
            thinking_level=settings.gemini_thinking_level,
        )
    if not settings.anthropic_api_key:
        raise ValueError("ANTHROPIC_API_KEY is required when VISION_PROVIDER=anthropic")
    from app.ai.anthropic_provider import AnthropicProvider

    return AnthropicProvider(
        api_key=settings.anthropic_api_key,
        model=settings.vision_model,
        effort=settings.vision_effort,
    )
