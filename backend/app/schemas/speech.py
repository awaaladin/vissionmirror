from typing import Literal

from pydantic import BaseModel, Field


class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1500)
    rate: float = Field(default=1.0, ge=0.5, le=2.0)
    voice: Literal["female", "male"] = "female"  # Adanna / Bola
    # What the text is written in. Hausa, Yoruba and Igbo are pronounced by the VoiceVerse API.
    language: str = Field(default="en", pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")
