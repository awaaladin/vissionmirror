from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile

from app.api.deps import get_service
from app.core.rate_limit import limited
from app.core.security import current_device
from app.schemas.common import DetailLevel
from app.schemas.describe import DescribeResponse
from app.services.describe_service import DescribeService
from app.services.image_utils import read_image

router = APIRouter(tags=["describe"])


@router.post("/describe", response_model=DescribeResponse)
async def describe(
    request: Request,
    image: Annotated[UploadFile, File()],
    detail_level: Annotated[DetailLevel, Form()] = "standard",
    language: Annotated[str, Form(pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")] = "en",
    device_id: str = Depends(current_device),
    _: None = Depends(limited("describe")),
    service: DescribeService = Depends(get_service),
) -> DescribeResponse:
    data, media_type = await read_image(image, request.app.state.settings.max_image_bytes)
    return await service.describe(
        device_id=device_id,
        image=data,
        media_type=media_type,
        detail_level=detail_level,
        language=language,
    )
