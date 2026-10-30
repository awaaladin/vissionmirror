"""Email: welcome, payment receipt, forgot-password. Nothing is sent: the SMTP step is replaced by a recorder."""
import hashlib
import hmac
import json
import re
from unittest import mock

import fakeredis
import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.core import passwords
from app.core.config import Settings
from app.main import create_app
from app.services import mailer
from app.services.user_store import MemoryUserStore
from tests.conftest import get_token
from tests.test_support import PAYSTACK_KEY, FakePaystack

SECRET = "test-secret-key-that-is-at-least-32-characters"
GOOD = {"email": "ada@example.com", "password": "correct horse battery", "display_name": "Ada"}


@pytest.fixture
def sent(monkeypatch):
    """Every email the app tries to send, as (to, subject, plain text)."""
    box: list[tuple[str, str, str]] = []

    def fake(settings, to, subject, headline, paragraphs, button):
        _, text = mailer.render(settings, headline, paragraphs, button)
        box.append((to, subject, text))
        return True

    monkeypatch.setattr(mailer, "_send_blocking", fake)
    return box


def make(**overrides):
    base = dict(secret_key=SECRET, _env_file=None, email_host_user="sender@example.com", email_host_password="app-password",
                email_from="VisionMirror <sender@example.com>", public_base_url="https://api.vm.test",
                paystack_secret_key=PAYSTACK_KEY)
    app = create_app(settings=Settings(**{**base, **overrides}), redis=fakeredis.FakeAsyncRedis(),
                     provider=MockProvider("outfit"), users=MemoryUserStore())
    app.state.paystack = FakePaystack()
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test"), app


async def register(c, **body):
    return await c.post("/v1/auth/register", json={**GOOD, **body})


def token_from(text: str) -> str:
    return re.search(r"token=([^\s]+)", text).group(1)


async def test_registering_sends_one_welcome_email(sent):
    c, _ = make()
    async with c:
        assert (await register(c)).status_code == 201
        assert (await register(c)).status_code == 409  # a duplicate sends nothing more
    assert [(to, subject) for to, subject, _ in sent] == [("ada@example.com", "Welcome to VisionMirror")]


async def test_nothing_is_sent_without_credentials(sent):
    c, _ = make(email_host_password="")
    async with c:
        assert (await register(c)).status_code == 201
    assert sent == []


async def test_a_broken_mail_server_never_breaks_signup(monkeypatch):
    monkeypatch.setattr(mailer, "_send_blocking", mock.Mock(side_effect=OSError("smtp down")))
    c, _ = make()
    async with c:
        r = await register(c)
    assert r.status_code == 201 and r.json()["access_token"]


def test_user_text_is_escaped():
    html, _ = mailer.render(Settings(secret_key=SECRET, _env_file=None, support_email="s@example.com"),
                            "<script>x</script>", ["<b>hi</b>"], ("Go", 'https://x.test/?a="b"'))
    assert "<script>" not in html and "&lt;script&gt;" in html and "<b>hi</b>" not in html


async def test_a_paid_support_payment_sends_exactly_one_receipt_however_often_it_is_reported(sent):
    c, app = make()
    async with c:
        ref = (await c.post("/v1/support/checkout", json={"amount_ngn": 500, "email": "ada@example.com"},
                            headers=await get_token(c))).json()["reference"]
        body = json.dumps({"event": "charge.success", "data": {"reference": ref, "amount": 50_000, "fees": 750,
                                                              "paid_at": "2026-10-24T11:00:00.000Z"}}).encode()
        sig = hmac.new(PAYSTACK_KEY.encode(), body, hashlib.sha512).hexdigest()
        for _ in range(2):
            await c.post("/v1/support/paystack/webhook", content=body, headers={"x-paystack-signature": sig})
        await c.get("/v1/support/status", params={"reference": ref})  # the return page reports it too
    receipts = [m for m in sent if "receipt" in m[1].lower()]
    assert len(receipts) == 1
    assert receipts[0][0] == "ada@example.com" and "₦500.00" in receipts[0][1] and ref in receipts[0][2]


async def test_an_unpaid_checkout_sends_no_receipt(sent):
    c, _ = make()
    async with c:
        await c.post("/v1/support/checkout", json={"amount_ngn": 500, "email": "ada@example.com"}, headers=await get_token(c))
    assert sent == []


async def test_reset_for_an_unknown_email_sends_nothing_and_answers_the_same(sent):
    c, _ = make()
    async with c:
        await register(c)
        sent.clear()
        a = await c.post("/v1/auth/password-reset", json={"email": "nobody@example.com"})
        b = await c.post("/v1/auth/password-reset", json={"email": "ADA@example.com"})
    assert a.status_code == b.status_code == 200 and a.json() == b.json()
    assert len(sent) == 1 and sent[0][0] == "ada@example.com"
    assert "https://api.vm.test/v1/auth/password-reset/page?token=" in sent[0][2]


async def test_reset_changes_the_password_once(sent):
    c, app = make()
    async with c:
        await register(c)
        await c.post("/v1/auth/password-reset", json={"email": "ada@example.com"})
        token = token_from(sent[-1][2])
        short = await c.post("/v1/auth/password-reset/confirm", json={"token": token, "password": "short"})
        assert short.status_code == 400
        ok = await c.post("/v1/auth/password-reset/confirm", json={"token": token, "password": "a brand new password"})
        assert ok.status_code == 200, ok.text
        old = await c.post("/v1/auth/login", json={"email": "ada@example.com", "password": GOOD["password"]})
        new = await c.post("/v1/auth/login", json={"email": "ada@example.com", "password": "a brand new password"})
        again = await c.post("/v1/auth/password-reset/confirm", json={"token": token, "password": "yet another password"})
    assert old.status_code == 401 and new.status_code == 200
    assert again.status_code == 400, "a reset link must work only once"


async def test_garbage_and_wrong_kind_of_token_are_refused(sent):
    c, _ = make()
    async with c:
        signed_up = (await register(c)).json()
        for bad in ("nope", signed_up["access_token"]):  # an ordinary sign-in token is not a reset token
            r = await c.post("/v1/auth/password-reset/confirm", json={"token": bad, "password": "a brand new password"})
            assert r.status_code == 400


async def test_the_web_page_from_the_email_works_once(sent):
    c, _ = make()
    async with c:
        await register(c)
        await c.post("/v1/auth/password-reset", json={"email": "ada@example.com"})
        token = token_from(sent[-1][2])
        page = await c.get("/v1/auth/password-reset/page", params={"token": token})
        assert page.status_code == 200 and "New password" in page.text
        form = {"content-type": "application/x-www-form-urlencoded"}
        mismatch = await c.post("/v1/auth/password-reset/page", content=f"token={token}&password=a+brand+new+password&confirm=other", headers=form)
        assert mismatch.status_code == 400 and "do not match" in mismatch.text
        done = await c.post("/v1/auth/password-reset/page", content=f"token={token}&password=a+brand+new+password&confirm=a+brand+new+password", headers=form)
        assert done.status_code == 200 and "Your password was changed" in done.text
        assert (await c.get("/v1/auth/password-reset/page", params={"token": token})).status_code == 400


async def test_the_request_page_sends_the_link_and_answers_neutrally(sent):
    c, _ = make()
    async with c:
        await register(c)
        sent.clear()
        assert (await c.get("/v1/auth/password-reset/request-page")).status_code == 200
        form = {"content-type": "application/x-www-form-urlencoded"}
        ok = await c.post("/v1/auth/password-reset/request-page", content="email=ada%40example.com", headers=form)
        unknown = await c.post("/v1/auth/password-reset/request-page", content="email=nobody%40example.com", headers=form)
        junk = await c.post("/v1/auth/password-reset/request-page", content="email=not-an-email", headers=form)
    assert all("we just sent a link" in r.text for r in (ok, unknown, junk))
    assert len(sent) == 1
