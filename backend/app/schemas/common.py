from typing import Literal

from pydantic import BaseModel

DetailLevel = Literal["brief", "standard", "detailed"]


class ErrorResponse(BaseModel):
    code: str
    message: str
    spoken_text: str
