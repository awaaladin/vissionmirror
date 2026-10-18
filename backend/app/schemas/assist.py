from typing import Literal

from pydantic import BaseModel, Field


class AssistRequest(BaseModel):
    query: str = Field(min_length=1, max_length=400)
    language: str = Field(default="en", pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")
    assistant: Literal["Adanna", "Bola"] = "Adanna"


class AssistResponse(BaseModel):
    kind: Literal["weather", "answer"]
    spoken_text: str
