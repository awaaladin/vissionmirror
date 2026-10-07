import uuid

from fastapi import APIRouter, Depends, Response

from app.api.deps import get_store
from app.core.errors import APIError
from app.core.rate_limit import limited
from app.core.security import current_device
from app.services.session_store import SessionStore

router = APIRouter(tags=["session"])


def session_gone() -> APIError:
    return APIError(
        404,
        "session_not_found",
        "Session not found or expired.",
        "I can't find that photo any more. Let's take a new one.",
    )


@router.delete("/session/{session_id}", status_code=204)
async def delete_session(
    session_id: uuid.UUID,
    device_id: str = Depends(current_device),
    _: None = Depends(limited("general")),
    store: SessionStore = Depends(get_store),
) -> Response:
    if not await store.delete(session_id, device_id):
        raise session_gone()
    return Response(status_code=204)
