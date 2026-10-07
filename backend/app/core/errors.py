from fastapi import Request
from fastapi.responses import JSONResponse

from app.schemas.common import ErrorResponse


class APIError(Exception):
    """Error carrying a machine code, a developer message and a line the app can speak."""

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        spoken_text: str,
        headers: dict[str, str] | None = None,
    ):
        self.status_code = status_code
        self.code = code
        self.message = message
        self.spoken_text = spoken_text
        self.headers = headers or {}


async def api_error_handler(_: Request, exc: APIError) -> JSONResponse:
    body = ErrorResponse(code=exc.code, message=exc.message, spoken_text=exc.spoken_text)
    return JSONResponse(body.model_dump(), status_code=exc.status_code, headers=exc.headers)
