import uuid
from typing import Literal

from pydantic import BaseModel


class AnonAuthRequest(BaseModel):
    device_id: uuid.UUID


class TokenResponse(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in: int
