import uuid

from pydantic import BaseModel

from app.schemas.auth import TokenResponse


class RegisterRequest(BaseModel):
    # Plain strings: the endpoint validates them so every complaint comes back with a speakable sentence.
    email: str
    password: str
    display_name: str | None = None


class LoginRequest(BaseModel):
    email: str
    password: str


class UserOut(BaseModel):
    id: uuid.UUID
    email: str
    display_name: str | None = None


class AccountAuthResponse(TokenResponse):
    user: UserOut
