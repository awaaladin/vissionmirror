from fastapi import APIRouter, Depends, Request

from app.core.rate_limit import limit_auth
from app.core.security import create_token
from app.schemas.auth import AnonAuthRequest, TokenResponse

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/anon", response_model=TokenResponse, dependencies=[Depends(limit_auth)])
async def anon(body: AnonAuthRequest, request: Request) -> TokenResponse:
    token, expires_in = create_token(body.device_id, request.app.state.settings)
    return TokenResponse(access_token=token, expires_in=expires_in)
