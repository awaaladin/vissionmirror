import hashlib
import hmac
import json

import fakeredis
import httpx

from app.ai.mock_provider import MockProvider
from app.core.config import Settings
from app.main import create_app
from app.services.user_store import MemoryUserStore
from tests.conftest import get_token

SECRET = "test-secret-key-that-is-at-least-32-characters"
PAYSTACK_KEY = "sk_test_unit"


class FakePaystack:
    """Stands in for app.services.paystack_service.Paystack; records what would be sent."""

    def __init__(self, status="success"):
        self.status, self.initialized, self.verified = status, [], []

    async def initialize(self, **kwargs):
        self.initialized.append(kwargs)
        return {"authorization_url": "https://checkout.paystack.com/x"}

    async def verify(self, reference):
        self.verified.append(reference)
        return {
            "reference": reference, "status": self.status, "amount": 200_000, "fees": 3_000,
            "paid_at": "2026-10-24T10:00:00.000Z", "customer": {"email": "ada@example.com"},
            "metadata": {"app": "VISIONMIRROR"},
        }

    def signature_ok(self, raw_body, signature):
        expected = hmac.new(PAYSTACK_KEY.encode(), raw_body, hashlib.sha512).hexdigest()
        return bool(signature) and hmac.compare_digest(expected, signature)


def make(paystack_key=PAYSTACK_KEY, fake=None, payments=None, return_url=""):
    settings = Settings(secret_key=SECRET, _env_file=None, paystack_secret_key=paystack_key,
                        public_base_url="https://api.visionmirror.test", support_return_url=return_url)
    app = create_app(settings=settings, redis=fakeredis.FakeAsyncRedis(), provider=MockProvider("outfit"),
                     users=MemoryUserStore(), payments=payments)
    app.state.paystack = fake or FakePaystack()
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
    client.app_state = app.state  # for tests that look inside
    return client, app.state.paystack


async def test_guest_checkout_names_the_app():
    client, fake = make()
    async with client:
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 2000, "email": "Ada@Example.com"},
                              headers=await get_token(client))
    assert r.status_code == 201, r.text
    assert r.json()["reference"].startswith("VISIONMIRROR-SUPPORT-")
    sent = fake.initialized[0]
    assert sent["amount_kobo"] == 200_000
    assert sent["email"] == "ada@example.com"
    assert sent["callback_url"] == "https://api.visionmirror.test/v1/support/return"
    assert sent["metadata"]["app"] == "VISIONMIRROR"


async def test_signed_in_account_email_is_used():
    client, fake = make()
    async with client:
        reg = await client.post("/v1/auth/register", json={"email": "bola@example.com", "password": "correct horse battery"})
        headers = {"Authorization": f"Bearer {reg.json()['access_token']}"}
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 1000}, headers=headers)
    assert r.status_code == 201, r.text
    assert fake.initialized[0]["email"] == "bola@example.com"


async def test_guest_without_email_is_asked_for_one():
    client, _ = make()
    async with client:
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 1000}, headers=await get_token(client))
    assert r.status_code == 400
    assert r.json()["code"] == "email_required"


async def test_amount_limits():
    client, _ = make()
    async with client:
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 10, "email": "a@example.com"},
                              headers=await get_token(client))
    assert r.json()["code"] == "invalid_amount"


async def test_not_configured_answers_503():
    client, _ = make(paystack_key="")
    async with client:
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 1000, "email": "a@example.com"},
                              headers=await get_token(client))
    assert r.status_code == 503


async def test_return_page_thanks_the_payer():
    client, _ = make()
    async with client:
        r = await client.get("/v1/support/return", params={"reference": "VISIONMIRROR-SUPPORT-20261023-ABC"})
    assert "Thank you" in r.text


async def test_webhook_checks_signature():
    client, _ = make()
    body = json.dumps({"event": "charge.success", "data": {"reference": "VISIONMIRROR-SUPPORT-X", "amount": 100}}).encode()
    good = hmac.new(PAYSTACK_KEY.encode(), body, hashlib.sha512).hexdigest()
    async with client:
        ok = await client.post("/v1/support/paystack/webhook", content=body, headers={"x-paystack-signature": good})
        bad = await client.post("/v1/support/paystack/webhook", content=body, headers={"x-paystack-signature": "x"})
    assert ok.status_code == 200
    assert bad.status_code == 400


async def test_the_key_can_be_named_paystack_api_as_in_the_env_file(monkeypatch):
    monkeypatch.setenv("PAYSTACK_API", "sk_test_from_the_env_file_name")
    monkeypatch.delenv("PAYSTACK_SECRET_KEY", raising=False)
    assert Settings(secret_key=SECRET, _env_file=None).paystack_secret_key == "sk_test_from_the_env_file_name"


async def test_health_says_whether_payments_are_on_but_never_the_key():
    on, _ = make()
    off, _ = make(paystack_key="")
    async with on, off:
        assert (await on.get("/v1/health")).json()["payments"] is True
        assert (await off.get("/v1/health")).json()["payments"] is False
        assert PAYSTACK_KEY not in (await on.get("/v1/health")).text


async def test_a_transaction_without_this_apps_name_is_never_sent():
    import httpx
    import pytest

    from app.services.paystack_service import Paystack, PaystackError, make_reference, paystack_metadata

    sent = []
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: sent.append(r) or httpx.Response(200, json={"status": True, "data": {}})))
    p = Paystack("sk_test_x", client=client)
    with pytest.raises(PaystackError):
        await p.initialize(email="a@b.co", amount_kobo=50000, reference="SOMEONE-ELSE-1", callback_url="https://x", metadata=paystack_metadata("SUPPORT", "x"))
    with pytest.raises(PaystackError):
        await p.initialize(email="a@b.co", amount_kobo=50000, reference=make_reference("SUPPORT"), callback_url="https://x", metadata={"app": "OTHER"})
    assert sent == []  # nothing reached Paystack
    await p.initialize(email="a@b.co", amount_kobo=50000, reference=make_reference("SUPPORT"), callback_url="https://x", metadata=paystack_metadata("SUPPORT", "x"))
    assert len(sent) == 1


def test_the_report_counts_only_visionmirror_and_only_paid_money():
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
    from paystack_report import summarise

    rows = [
        {"reference": "VISIONMIRROR-SUPPORT-20261023-AAA", "status": "success", "amount": 200000, "fees": 3000, "paid_at": "2026-10-23T10:00:00Z", "metadata": {"app": "VISIONMIRROR", "purpose": "SUPPORT"}},
        {"reference": "VISIONMIRROR-SUPPORT-20261024-BBB", "status": "abandoned", "amount": 500000, "fees": 0, "metadata": {"app": "VISIONMIRROR", "purpose": "SUPPORT"}},
        {"reference": "NUTRISCAN-SUB-20261023-CCC", "status": "success", "amount": 999900, "fees": 10000, "paid_at": "2026-10-23T11:00:00Z", "metadata": {"app": "NUTRISCAN"}},
        {"reference": "VISIONMIRROR-SUPPORT-20261105-DDD", "status": "success", "amount": 100000, "fees": 1500, "paid_at": "2026-11-05T09:00:00Z", "metadata": {"app": "VISIONMIRROR", "purpose": "SUPPORT"}},
    ]
    s = summarise(rows)
    assert (s["transactions"], s["paid"], s["not_paid"]) == (3, 2, 1)
    assert (s["gross_kobo"], s["fees_kobo"], s["net_kobo"]) == (300000, 4500, 295500)
    assert s["by_month"] == {"2026-10": 200000, "2026-11": 100000}
    assert s["by_purpose"] == {"SUPPORT": 300000}


# ------------------------------------------------------------------ the checkout on the website, saved against the sign-in email

async def test_the_payment_is_saved_against_the_email_and_linked_to_the_account():
    client, fake = make()
    async with client:
        reg = await client.post("/v1/auth/register", json={"email": "bola@example.com", "password": "correct horse battery"})
        assert reg.status_code in (200, 201), reg.text
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 2000, "email": "Bola@Example.com"},
                              headers=await get_token(client))
        row = await client.app_state.payments.get(r.json()["reference"])
        account = (await client.app_state.users.find_by_email("bola@example.com"))[0]
    assert row.email == "bola@example.com" and row.status == "pending" and row.amount_kobo == 200_000
    assert row.user_id == account.id  # the gift belongs to the account that signs in with this email
    assert fake.initialized[0]["metadata"]["account_email"] == "bola@example.com"  # and shows in Paystack's own records


async def test_status_confirms_with_paystack_marks_it_paid_and_hides_the_email():
    client, fake = make()
    async with client:
        ref = (await client.post("/v1/support/checkout", json={"amount_ngn": 2000, "email": "ada@example.com"},
                                 headers=await get_token(client))).json()["reference"]
        first = (await client.get("/v1/support/status", params={"reference": ref})).json()
        again = (await client.get("/v1/support/status", params={"reference": ref})).json()
        row = await client.app_state.payments.get(ref)
    assert first == again == {"reference": ref, "paid": True, "amount_ngn": 2000, "email_hint": "a***@example.com"}
    assert row.status == "success" and row.fees_kobo == 3_000 and row.paid_at is not None


async def test_status_for_an_unpaid_or_foreign_reference_says_not_paid():
    fake = FakePaystack(status="abandoned")
    client, _ = make(fake=fake)
    async with client:
        ref = (await client.post("/v1/support/checkout", json={"amount_ngn": 1000, "email": "ada@example.com"},
                                 headers=await get_token(client))).json()["reference"]
        unpaid = (await client.get("/v1/support/status", params={"reference": ref})).json()
        foreign = (await client.get("/v1/support/status", params={"reference": "NUTRISCAN-PRO-20261024-AAAAAAAAAAAA"})).json()
        row = await client.app_state.payments.get(ref)
    assert unpaid["paid"] is False and foreign["paid"] is False
    assert row.status == "pending"
    assert fake.verified == [ref]  # a reference that is not VisionMirror's is never even sent to Paystack


async def test_the_webhook_marks_the_saved_payment_paid():
    client, _ = make()
    async with client:
        ref = (await client.post("/v1/support/checkout", json={"amount_ngn": 500, "email": "ada@example.com"},
                                 headers=await get_token(client))).json()["reference"]
        body = json.dumps({"event": "charge.success", "data": {"reference": ref, "amount": 50_000, "fees": 750,
                                                                  "paid_at": "2026-10-24T11:00:00.000Z"}}).encode()
        sig = hmac.new(PAYSTACK_KEY.encode(), body, hashlib.sha512).hexdigest()
        r = await client.post("/v1/support/paystack/webhook", content=body, headers={"x-paystack-signature": sig})
        row = await client.app_state.payments.get(ref)
    assert r.status_code == 200
    assert (row.status, row.amount_kobo, row.fees_kobo) == ("success", 50_000, 750)


async def test_the_payer_goes_back_to_the_websites_thank_you_page():
    client, fake = make(return_url="https://visionmirror-app.vercel.app/thanks")
    async with client:
        await client.post("/v1/support/checkout", json={"amount_ngn": 1000, "email": "ada@example.com"}, headers=await get_token(client))
    assert fake.initialized[0]["callback_url"] == "https://visionmirror-app.vercel.app/thanks"


async def test_a_broken_database_never_stops_someone_giving():
    from app.services.payment_store import PaymentStore
    from app.services.user_store import AccountsUnavailable

    class Broken(PaymentStore):
        async def record(self, *a, **k): raise AccountsUnavailable
        async def mark_paid(self, *a, **k): raise AccountsUnavailable
        async def get(self, *a, **k): raise AccountsUnavailable
        async def for_email(self, *a, **k): raise AccountsUnavailable

    client, _ = make(payments=Broken())
    async with client:
        r = await client.post("/v1/support/checkout", json={"amount_ngn": 1000, "email": "ada@example.com"}, headers=await get_token(client))
        ref = r.json()["reference"]
        status = await client.get("/v1/support/status", params={"reference": ref})
    assert r.status_code == 201
    assert status.status_code == 200 and status.json()["paid"] is True  # Paystack says paid; the record is secondary


def test_emails_are_masked_for_the_thank_you_page():
    from app.services.payment_store import mask_email

    assert mask_email("ada.obi@gmail.com") == "a***@gmail.com"
    assert mask_email("nonsense") == "***"

