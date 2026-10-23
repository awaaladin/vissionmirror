"""Paystack for one-time support payments (VisionMirror stays free; this only takes gifts).

One Paystack account serves several apps, so every VisionMirror reference starts with the app
code and every transaction carries the same metadata keys. That lets the dashboard, exports and
the webhook router tell VisionMirror's payments apart from the other apps':

    VISIONMIRROR-SUPPORT-20261023-4F9A2C7B1E0D

The router forwards only VISIONMIRROR- references here, byte for byte, so the HMAC signature
still verifies against the shared secret key.
"""

import hashlib
import hmac
import uuid
from datetime import UTC, datetime

import httpx

APP_CODE = "VISIONMIRROR"
APP_NAME = "VisionMirror"


class PaystackError(Exception):
    pass


def make_reference(purpose: str, *, length: int = 12) -> str:
    stamp = datetime.now(UTC).strftime("%Y%m%d")
    return f"{APP_CODE}-{purpose.upper()}-{stamp}-{uuid.uuid4().hex[:length].upper()}"


def is_ours(reference: str | None) -> bool:
    return (reference or "").upper().startswith(f"{APP_CODE}-")


def paystack_metadata(purpose: str, description: str, **extra: object) -> dict:
    """Metadata sent with every transaction. custom_fields show on the Paystack dashboard."""
    return {
        "app": APP_CODE,
        "app_name": APP_NAME,
        "source": "visionmirror-api",
        "purpose": purpose.upper(),
        **extra,
        "custom_fields": [
            {"display_name": "App", "variable_name": "app", "value": APP_NAME},
            {"display_name": "Payment for", "variable_name": "purpose", "value": description[:200]},
        ],
    }


class Paystack:
    def __init__(self, secret_key: str, base_url: str = "https://api.paystack.co", client: httpx.AsyncClient | None = None):
        self.secret_key = secret_key
        self.base_url = base_url.rstrip("/")
        self.client = client or httpx.AsyncClient(timeout=httpx.Timeout(20.0, connect=10.0))

    async def _request(self, method: str, path: str, **kwargs) -> dict:
        try:
            response = await self.client.request(
                method, f"{self.base_url}{path}", headers={"Authorization": f"Bearer {self.secret_key}"}, **kwargs
            )
            body = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise PaystackError(f"Paystack unreachable: {exc}") from exc
        if response.is_error or not body.get("status"):
            raise PaystackError(body.get("message") or f"Paystack error {response.status_code}")
        return body["data"]

    async def initialize(self, *, email: str, amount_kobo: int, reference: str, callback_url: str, metadata: dict) -> dict:
        # The account is shared with five other apps. A transaction without this app's name could never be told apart in
        # the books, so refuse to send one, whatever code asks.
        if not is_ours(reference) or metadata.get("app") != APP_CODE:
            raise PaystackError(f"Refusing an untagged transaction: references must start with {APP_CODE}- and carry metadata.app")
        return await self._request("POST", "/transaction/initialize", json={
            "email": email, "amount": amount_kobo, "currency": "NGN", "reference": reference,
            "callback_url": callback_url, "metadata": metadata,
        })

    async def verify(self, reference: str) -> dict:
        return await self._request("GET", f"/transaction/verify/{reference}")

    def signature_ok(self, raw_body: bytes, signature: str | None) -> bool:
        if not self.secret_key or not signature:
            return False
        expected = hmac.new(self.secret_key.encode(), raw_body, hashlib.sha512).hexdigest()
        return hmac.compare_digest(expected, signature)
