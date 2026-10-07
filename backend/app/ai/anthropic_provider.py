from typing import Any

import anthropic
from pydantic import BaseModel

from app.ai.structured import ATTEMPTS, StructuredProvider, log, unavailable

__all__ = ["AnthropicProvider", "ATTEMPTS"]

MAX_OUTPUT_TOKENS = 4096


class AnthropicProvider(StructuredProvider):
    def __init__(
        self,
        *,
        api_key: str | None = None,
        model: str,
        effort: str = "medium",
        client: Any | None = None,
    ):
        self.model = model
        self.effort = effort
        # `client` is injectable for tests; otherwise the SDK client (it retries 429/5xx itself).
        self.client = client or anthropic.AsyncAnthropic(api_key=api_key)

    async def _call(self, system: str, messages: list[dict], model_cls: type[BaseModel]) -> str:
        schema = anthropic.transform_schema(model_cls)
        try:
            response = await self.client.messages.create(
                model=self.model,
                max_tokens=MAX_OUTPUT_TOKENS,
                system=system,
                messages=[_to_anthropic(m) for m in messages],
                output_config={"effort": self.effort, "format": {"type": "json_schema", "schema": schema}},
            )
        except anthropic.APIStatusError as exc:
            log.error("anthropic status error %s request_id=%s", exc.status_code, getattr(exc, "request_id", None))
            raise unavailable(f"AI provider returned HTTP {exc.status_code}.") from exc
        except anthropic.APIConnectionError as exc:
            log.error("anthropic connection error: %s", type(exc).__name__)
            raise unavailable("Could not reach the AI provider.") from exc

        if response.stop_reason == "refusal":
            raise unavailable("The AI provider declined this request.")
        if response.stop_reason == "max_tokens":
            raise unavailable("The AI response was cut off.")
        text = next((b.text for b in response.content if b.type == "text"), None)
        if text is None:
            raise unavailable("The AI response had no text.")
        return text


def _to_anthropic(message: dict) -> dict:
    content = []
    for part in message["parts"]:
        if part["type"] == "image":
            content.append(
                {
                    "type": "image",
                    "source": {"type": "base64", "media_type": part["media_type"], "data": part["data"]},
                }
            )
        else:
            content.append({"type": "text", "text": part["text"]})
    return {"role": message["role"], "content": content}
