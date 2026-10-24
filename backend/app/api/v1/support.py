"""Support VisionMirror: a one-time gift through Paystack. Nothing is unlocked by paying.

The checkout lives on the website (the landing page). The payer gives the email they sign in to the app with; the payment is
named VISIONMIRROR-... on the shared Paystack account (see paystack_service.py) and saved against that email here, so the gift
belongs to that account's owner. Paystack emails the receipt to the same address.
"""

import datetime as dt
import html
import json
import logging
import uuid

from email_validator import EmailNotValidError, validate_email
from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from app.core.errors import APIError
from app.core.rate_limit import limit_auth, limited
from app.core.security import current_device
from app.services.payment_store import best_effort, mask_email
from app.services.paystack_service import APP_CODE, Paystack, PaystackError, is_ours, make_reference, paystack_metadata
from app.services.user_store import AccountsUnavailable

log = logging.getLogger("visionmirror.support")

router = APIRouter(prefix="/support", tags=["support"])


class SupportCheckoutRequest(BaseModel):
    amount_ngn: int
    # The email the person signs in to the app with. A signed-in account's own email is used when this is left out.
    email: str | None = None


class SupportCheckoutResponse(BaseModel):
    reference: str
    authorization_url: str


class SupportStatus(BaseModel):
    reference: str
    paid: bool
    amount_ngn: int | None = None
    email_hint: str | None = None  # a***@gmail.com


def _paystack(request: Request) -> Paystack:
    settings = request.app.state.settings
    if not settings.paystack_secret_key:
        raise APIError(503, "payments_unavailable", "Paystack is not configured.",
                       "Payments aren't available right now. Thank you for wanting to help.")
    paystack = getattr(request.app.state, "paystack", None)
    if paystack is None:
        paystack = request.app.state.paystack = Paystack(settings.paystack_secret_key, settings.paystack_base_url)
    return paystack


def _return_url(settings) -> str:
    return settings.support_return_url or f"{settings.public_base_url.rstrip('/')}/v1/support/return"


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


async def _account_id(request: Request, email: str) -> uuid.UUID | None:
    """The account that signs in with this email, if there is one (the gift is then linked to it)."""
    try:
        found = await request.app.state.users.find_by_email(email)
    except Exception:  # noqa: BLE001 - linking is a nicety; the gift goes ahead without it
        return None
    return found[0].id if found else None


def _when(value: str | None) -> dt.datetime | None:
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00")) if value else None
    except ValueError:
        return None


async def _confirm(request: Request, reference: str) -> dict | None:
    """Ask Paystack whether this reference is a successful VisionMirror payment. Saves it as paid when it is. None otherwise."""
    if not is_ours(reference) or not request.app.state.settings.paystack_secret_key:
        return None
    try:
        data = await _paystack(request).verify(reference)
    except PaystackError as exc:
        log.warning("Paystack verify failed for %s: %s", reference, exc)
        return None
    ours = (
        data.get("status") == "success"
        and str(data.get("reference", "")).upper() == reference.upper()
        and (data.get("metadata") or {}).get("app") == APP_CODE
    )
    if not ours:
        return None
    await best_effort(
        request.app.state.payments.mark_paid(reference, data.get("amount"), data.get("fees"), _when(data.get("paid_at"))),
        "mark paid",
    )
    return data


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
            callback_url=_return_url(settings),
            metadata=paystack_metadata("SUPPORT", "Support for VisionMirror", account_email=email),
        )
    except PaystackError as exc:
        log.error("Paystack initialize failed for %s: %s", reference, exc)
        raise APIError(502, "payment_failed", "Payment couldn't be started.",
                       "I couldn't start the payment. Please try again in a moment.")
    # Saved against the email, as pending, until Paystack says it was paid.
    await best_effort(
        request.app.state.payments.record(reference, email, body.amount_ngn * 100, "SUPPORT", await _account_id(request, email)),
        "record",
    )
    return SupportCheckoutResponse(reference=reference, authorization_url=data["authorization_url"])


@router.get("/status", response_model=SupportStatus, dependencies=[Depends(limit_auth)])
async def status(request: Request, reference: str = "") -> SupportStatus:
    """For the website's thank-you page: did this payment really go through? Anyone can ask; the answer shows only a masked email."""
    data = await _confirm(request, reference)
    if data is None:
        return SupportStatus(reference=reference[:80], paid=False)
    record = await best_effort(request.app.state.payments.get(reference), "get")
    email = record.email if record else (data.get("customer") or {}).get("email", "")
    return SupportStatus(
        reference=reference, paid=True, amount_ngn=int(data.get("amount", 0)) // 100, email_hint=mask_email(email) if email else None,
    )


@router.get("/return", response_class=HTMLResponse, include_in_schema=False)
async def payment_return(request: Request, reference: str = "") -> HTMLResponse:
    """A plain thank-you page, for when the payer is sent back to this API instead of the website."""
    paid = await _confirm(request, reference) is not None
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
    reference = data.get("reference")
    if payload.get("event") == "charge.success" and is_ours(reference):
        # Gifts unlock nothing, so the only thing to do is keep the record: paid, with Paystack's own amount and fee.
        log.info("Support payment received: %s (%s kobo)", reference, data.get("amount"))
        await best_effort(
            request.app.state.payments.mark_paid(reference, data.get("amount"), data.get("fees"), _when(data.get("paid_at"))),
            "mark paid (webhook)",
        )
    return {"received": True}
