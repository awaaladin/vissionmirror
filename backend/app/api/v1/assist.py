from fastapi import APIRouter, Depends, Request

from app.core.rate_limit import limited
from app.core.security import current_device
from app.services.access import guard
from app.schemas.assist import AssistRequest, AssistResponse
from app.services.assist_service import Where

router = APIRouter(tags=["assist"])


def _where(request: Request) -> Where | None:
    """Approximate place from the hosting platform's headers (city level). None when it does not provide them."""
    h = request.headers
    try:
        return Where(h.get("x-vercel-ip-city", "your area"), float(h["x-vercel-ip-latitude"]), float(h["x-vercel-ip-longitude"]))
    except (KeyError, ValueError):
        return None


@router.post("/assist", response_model=AssistResponse)
async def assist(
    body: AssistRequest, request: Request, subject: str = Depends(current_device), _: None = Depends(limited("ask")),
) -> AssistResponse:
    """The weather and general questions, for the voice assistant. Never fails because a lookup did: the text says so."""
    async with guard(request, subject, "assist"):
        kind, text = await request.app.state.assist.handle(query=body.query, language=body.language, here=_where(request), assistant=body.assistant)
    return AssistResponse(kind=kind, spoken_text=text)
