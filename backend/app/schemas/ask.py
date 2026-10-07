import uuid

from pydantic import BaseModel, Field


class AskRequest(BaseModel):
    session_id: uuid.UUID
    question: str = Field(min_length=1, max_length=500)
    language: str = Field(default="en", pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")


class AskResponse(BaseModel):
    answer_text: str
    spoken_text: str
    confidence: float = Field(ge=0.0, le=1.0)
