from typing import Annotated, Literal

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from pydantic import BaseModel

from app.api.deps import get_service
from app.core.rate_limit import limited
from app.services.describe_service import DescribeService
from app.services.image_utils import read_image

router = APIRouter(tags=["read"])


class ReadResponse(BaseModel):
    spoken_text: str


@router.post("/read", response_model=ReadResponse)
async def read(
    request: Request,
    image: Annotated[UploadFile, File()],
    ocr_text: Annotated[str, Form(max_length=4000)] = "",
    language: Annotated[str, Form(pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")] = "en",
    assistant: Annotated[Literal["Adanna", "Bola"], Form()] = "Adanna",
    _: None = Depends(limited("describe")),
    service: DescribeService = Depends(get_service),
) -> ReadResponse:
    """Reads the writing in a photo aloud, making sense of mistakes, cut-off words and abbreviations. Nothing is stored."""
    data, media_type = await read_image(image, request.app.state.settings.max_image_bytes)
    text = await service.read(image=data, media_type=media_type, ocr_text=ocr_text, language=language, assistant=assistant)
    return ReadResponse(spoken_text=text)
