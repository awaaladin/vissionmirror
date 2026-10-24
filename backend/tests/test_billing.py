import datetime as dt
import uuid

import fakeredis
import httpx

from app.ai.mock_provider import MockProvider
from app.ai.structured import weak_describe, weak_read
from app.core.config import Settings
from app.main import create_app
from app.services.entitlement import Entitlements, paid_until
from app.services.payment_store import MemoryPaymentStore, PaymentRecord
from app.services.usage import UsageMeter
from app.services.user_store import MemoryUserStore
from tests.conftest import JPEG, get_token

SECRET = "test-secret-key-that-is-at-least-32-characters"
T0 = dt.datetime(2026, 10, 24, 12, 0, tzinfo=dt.timezone.utc)


def row(days_ago, status="success", purpose="MONTH"):
    paid = T0 - dt.timedelta(days=days_ago)
    return PaymentRecord(reference=f"R{days_ago}", email="a@b.ng", user_id=None, purpose=purpose, amount_kobo=450000,
                         fees_kobo=None, status=status, created_at=paid, paid_at=paid)


def test_payments_stack_and_pending_ones_do_not_count():
    assert paid_until([row(5, status="pending")], 31) is None
    assert paid_until([row(5)], 31) == T0 - dt.timedelta(days=5) + dt.timedelta(days=31)
    two = paid_until([row(5), row(3)], 31)
    assert two == T0 - dt.timedelta(days=5) + dt.timedelta(days=62)
    assert paid_until([row(5, purpose="SUPPORT")], 31) is None  # a gift unlocks nothing


async def test_meter_counts_per_person_and_refuses_politely():
    meter = UsageMeter(fakeredis.FakeAsyncRedis(), SECRET, 2, now=lambda: T0)
    for _ in range(2):
        async with meter.action("p1", "describe"):
            pass
    assert await meter.used_today("p1") == 2 and await meter.used_today("p2") == 0
    try:
        async with meter.action("p1", "ask"):
            raise AssertionError("should have been refused")
    except Exception as exc:
        assert exc.status_code == 429 and exc.code == "fair_use_limit" and "midnight" in exc.spoken_text


async def test_failed_action_is_not_counted():
    meter = UsageMeter(fakeredis.FakeAsyncRedis(), SECRET, 5, now=lambda: T0)
    try:
        async with meter.action("p1", "describe"):
            raise RuntimeError("AI down")
    except RuntimeError:
        pass
    assert await meter.used_today("p1") == 0


async def test_day_resets_at_lagos_midnight():
    r = fakeredis.FakeAsyncRedis()
    late = dt.datetime(2026, 10, 24, 22, 30, tzinfo=dt.timezone.utc)  # 23:30 in Lagos
    m = UsageMeter(r, SECRET, 1, now=lambda: late)
    await m.add("p", "ask")
    assert await m.used_today("p") == 1
    assert await UsageMeter(r, SECRET, 1, now=lambda: late + dt.timedelta(hours=1)).used_today("p") == 0


async def test_entitlement_paid_trial_and_none():
    s = Settings(secret_key=SECRET, _env_file=None, trial_days=7)
    store = MemoryPaymentStore()
    ents = Entitlements(s, store, fakeredis.FakeAsyncRedis(), lambda: None, now=lambda: T0)
    new = await ents.for_email("a@b.ng", T0 - dt.timedelta(days=2))
    assert new.active and new.source == "trial"
    old = await ents.for_email("a@b.ng", T0 - dt.timedelta(days=30))
    assert not old.active
    await store.record("VISIONMIRROR-MONTH-1", "a@b.ng", 450000, "MONTH", None)
    await store.mark_paid("VISIONMIRROR-MONTH-1", 450000, 6750, T0 - dt.timedelta(days=1))
    paid = await ents.for_email("a@b.ng", T0 - dt.timedelta(days=30))
    assert paid.active and paid.source == "paid"


def app_client(**settings):
    app = create_app(settings=Settings(secret_key=SECRET, _env_file=None, **settings), redis=fakeredis.FakeAsyncRedis(),
                     provider=MockProvider("outfit"), users=MemoryUserStore())
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


async def test_fair_use_limit_over_http():
    async with app_client(fair_use_daily_actions=2) as c:
        h = await get_token(c)
        for _ in range(2):
            assert (await c.post("/v1/describe", headers=h, files={"image": ("a.jpg", JPEG, "image/jpeg")})).status_code == 200
        r = await c.post("/v1/describe", headers=h, files={"image": ("a.jpg", JPEG, "image/jpeg")})
    assert r.status_code == 429 and r.json()["code"] == "fair_use_limit"
    assert "fair share" in r.json()["spoken_text"]


async def test_subscription_enforced_for_guests_after_a_few_tries():
    async with app_client(require_subscription=True, guest_actions_per_day=1) as c:
        h = await get_token(c)
        ok = await c.post("/v1/describe", headers=h, files={"image": ("a.jpg", JPEG, "image/jpeg")})
        r = await c.post("/v1/describe", headers=h, files={"image": ("a.jpg", JPEG, "image/jpeg")})
    assert ok.status_code == 200
    assert r.status_code == 402 and r.json()["code"] == "subscription_required" and "4,500" in r.json()["spoken_text"]


async def test_plan_endpoint_and_month_checkout_uses_the_fixed_price():
    from tests.test_support import make
    client, fake = make()
    async with client:
        plan = (await client.get("/v1/support/plan")).json()
        r = await client.post("/v1/support/checkout", json={"mode": "month", "email": "ada@example.com", "amount_ngn": 1},
                              headers=await get_token(client))
        no_plan = await client.post("/v1/support/checkout", json={"mode": "subscribe", "email": "ada@example.com"},
                                    headers=await get_token(client))
    assert plan["price_ngn"] == 4500 and plan["renewal"] is False
    assert r.status_code == 201 and r.json()["reference"].startswith("VISIONMIRROR-MONTH-")
    assert fake.initialized[0]["amount_kobo"] == 450_000
    assert no_plan.status_code == 503


async def test_weak_answers_are_escalated_only_when_needed():
    a = await MockProvider("outfit").describe(image=JPEG, media_type="image/jpeg", detail_level="detailed", language="en")
    assert weak_describe(a) is None
    a.confidence = 0.3
    assert "confidence" in weak_describe(a)
    assert weak_read("word " * 30, "short") is not None
    assert weak_read("word " * 30, "word " * 25) is None
    assert weak_read("hi", "I can't read this.") is not None
