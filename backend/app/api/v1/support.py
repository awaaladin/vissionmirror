"""Support VisionMirror: a one-time gift through Paystack. Nothing is unlocked by paying."""

import html
import json
import logging
import uuid

from email_validator import EmailNotValidError, validate_email
from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from app.core.errors import APIError
from app.core.rate_limit import limited
from app.core.security import current_device
from app.services.paystack_service import Paystack, PaystackError, is_ours, make_reference, paystack_metadata
from app.services.user_store import AccountsUnavailable

log = logging.getLogger("visionmirror.support")

router = APIRouter(prefix="/support", tags=["support"])


class SupportCheckoutRequest(BaseModel):
    amount_ngn: int
    # Needed for a guest; a signed-in account's own email is used when this is left out.
    email: str | None = None


class SupportCheckoutResponse(BaseModel):
    reference: str
    authorization_url: str


def _paystack(request: Request) -> Paystack:
    settings = request.app.state.settings
    if not settings.paystack_secret_key:
        raise APIError(503, "payments_unavailable", "Paystack is not configured.",
                       "Payments aren't available right now. Thank you for wanting to help.")
    paystack = getattr(request.app.state, "paystack", None)
    if paystack is None:
        paystack = request.app.state.paystack = Paystack(settings.paystack_secret_key, settings.paystack_base_url)
    return paystack


async def _email_for(request: Request, subject: str, given: str | None) -> str:
    if given:
        try:
            return validate_email(given.strip(), check_deliverability=False).normalized.lower()
        except EmailNotValidError:
            raise APIError(400, "invalid_input", "Invalid email address.",
                           "That email address doesn't look right. Please check it and try again.")
    try:
        user = await request.app.state.users.get(uuid.UUID(subject))  # None for a guest's device ID
    except (ValueError, AccountsUnavailable):
        user = None
    if user is None:
        raise APIError(400, "email_required", "An email is required for a receipt.",
                       "Please give an email address so Paystack can send your receipt.")
    return user.email


@router.post("/checkout", response_model=SupportCheckoutResponse, status_code=201,
             dependencies=[Depends(limited("general"))])
async def checkout(
    body: SupportCheckoutRequest, request: Request, subject: str = Depends(current_device)
) -> SupportCheckoutResponse:
    settings = request.app.state.settings
    if not settings.support_min_ngn <= body.amount_ngn <= settings.support_max_ngn:
        raise APIError(400, "invalid_amount",
                       f"Amount must be {settings.support_min_ngn} to {settings.support_max_ngn} naira.",
                       f"Please choose an amount between {settings.support_min_ngn} and {settings.support_max_ngn} naira.")
    paystack = _paystack(request)
    email = await _email_for(request, subject, body.email)
    reference = make_reference("SUPPORT")
    try:
        data = await paystack.initialize(
            email=email,
            amount_kobo=body.amount_ngn * 100,
            reference=reference,
            callback_url=f"{settings.public_base_url.rstrip('/')}/v1/support/return",
            metadata=paystack_metadata("SUPPORT", "Support for VisionMirror"),
        )
    except PaystackError as exc:
        log.error("Paystack initialize failed for %s: %s", reference, exc)
        raise APIError(502, "payment_failed", "Payment couldn't be started.",
                       "I couldn't start the payment. Please try again in a moment.")
    return SupportCheckoutResponse(reference=reference, authorization_url=data["authorization_url"])


@router.get("/return", response_class=HTMLResponse, include_in_schema=False)
async def payment_return(request: Request, reference: str = "") -> HTMLResponse:
    """Where Paystack's checkout sends the payer's browser."""
    paid = False
    if is_ours(reference) and request.app.state.settings.paystack_secret_key:
        try:
            data = await _paystack(request).verify(reference)
            paid = (
                data.get("status") == "success"
                and str(data.get("reference", "")).upper() == reference.upper()
                and (data.get("metadata") or {}).get("app") == "VISIONMIRROR"
            )
        except PaystackError as exc:
            log.warning("Paystack verify failed for %s: %s", reference, exc)
    title = "Thank you" if paid else "Payment not confirmed yet"
    message = (
        "Your support keeps VisionMirror free for blind and low-vision people."
        if paid
        else "If you paid, Paystack will email your receipt shortly."
    )
    return HTMLResponse(
        f'<!doctype html><html lang="en"><head><meta charset="utf-8">'
        f'<meta name="viewport" content="width=device-width, initial-scale=1"><title>{title} | VisionMirror</title></head>'
        f'<body style="font-family:system-ui,sans-serif;max-width:28rem;margin:4rem auto;padding:0 1rem;text-align:center">'
        f"<h1>{title}</h1><p>{message}</p><p>Reference: {html.escape(reference)}</p></body></html>"
    )


@router.post("/paystack/webhook", include_in_schema=False)
async def paystack_webhook(request: Request) -> dict:
    raw = await request.body()
    if not _paystack(request).signature_ok(raw, request.headers.get("x-paystack-signature")):
        raise APIError(400, "invalid_signature", "Invalid signature.", "")
    try:
        payload = json.loads(raw)
    except ValueError:
        raise APIError(400, "invalid_input", "Invalid JSON.", "")
    data = payload.get("data") or {}
    if payload.get("event") == "charge.success" and is_ours(data.get("reference")):
        # Gifts unlock nothing, so there is nothing to apply: the record lives in Paystack.
        log.info("Support payment received: %s (%s kobo)", data.get("reference"), data.get("amount"))
    return {"received": True}
