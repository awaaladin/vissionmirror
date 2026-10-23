from fastapi import APIRouter, Request

router = APIRouter(tags=["health"])


@router.get("/health")
async def health(request: Request) -> dict:
    return {"status": "ok", "version": "1", "payments": bool(request.app.state.settings.paystack_secret_key)}
