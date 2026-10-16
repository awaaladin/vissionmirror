from pydantic import BaseModel, Field


class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1500)
    rate: float = Field(default=1.0, ge=0.5, le=2.0)
