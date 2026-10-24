from fastapi import APIRouter, Depends, Request

from app.api.deps import get_service
from app.api.v1.session import session_gone
from app.core.rate_limit import limited
from app.core.security import current_device
from app.services.access import guard
from app.schemas.ask import AskRequest, AskResponse
from app.services.describe_service import DescribeService, SessionNotFound

router = APIRouter(tags=["ask"])


@router.post("/ask", response_model=AskResponse)
async def ask(
    body: AskRequest,
    request: Request,
    device_id: str = Depends(current_device),
    _: None = Depends(limited("ask")),
    service: DescribeService = Depends(get_service),
) -> AskResponse:
    try:
        async with guard(request, device_id, "ask"):
            return await service.ask(device_id=device_id, req=body)
    except SessionNotFound:
        raise session_gone()
