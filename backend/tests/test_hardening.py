import datetime as dt
import json
import logging
import tempfile
import uuid

import fakeredis
import httpx
import pytest

from app.ai.mock_provider import MockProvider
from app.ai.provider import ProviderError, VisionProvider
from app.core.config import Settings
from app.core.logging import JsonFormatter
from app.core.rate_limit import DailyCap, RateLimiter
from app.main import create_app
from tests.conftest import JPEG, get_token

SECRET = "test-secret-key-that-is-at-least-32-characters"


def make_client(redis=None, provider=None, **overrides) -> tuple[httpx.AsyncClient, object]:
    settings = Settings(secret_key=SECRET, _env_file=None, **overrides)
    redis = redis or fakeredis.FakeAsyncRedis()
    app = create_app(settings=settings, redis=redis, provider=provider or MockProvider("outfit"))
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test"), redis


def files(data=JPEG):
    return {"image": ("me.jpg", data, "image/jpeg")}


async def describe(client, auth):
    return await client.post("/v1/describe", headers=auth, files=files())


class FailingProvider(VisionProvider):
    async def describe(self, **kw):
        raise ProviderError(502, "ai_unavailable", "boom", "try later")

    async def ask(self, **kw):
        raise ProviderError(502, "ai_unavailable", "boom", "try later")


# ---------- rate limits ----------


async def test_describe_limited_per_device_with_retry_after_and_spoken_text():
    client, _ = make_client(rate_describe_device_per_min=2)
    auth = await get_token(client)
    assert (await describe(client, auth)).status_code == 200
    assert (await describe(client, auth)).status_code == 200
    r = await describe(client, auth)
    assert r.status_code == 429
    assert 1 <= int(r.headers["retry-after"]) <= 60
    body = r.json()
    assert body["code"] == "rate_limited"
    assert "wait" in body["spoken_text"] and r.headers["retry-after"] in body["spoken_text"]


async def test_limit_is_per_device_not_global():
    client, _ = make_client(rate_describe_device_per_min=1)
    a, b = await get_token(client), await get_token(client)
    assert (await describe(client, a)).status_code == 200
    assert (await describe(client, a)).status_code == 429
    assert (await describe(client, b)).status_code == 200


async def test_limit_is_per_ip_across_devices():
    client, _ = make_client(rate_describe_ip_per_min=2)
    for _ in range(2):
        assert (await describe(client, await get_token(client))).status_code == 200
    assert (await describe(client, await get_token(client))).status_code == 429


async def test_describe_limit_does_not_block_ask_or_delete():
    client, _ = make_client(rate_describe_device_per_min=1)
    auth = await get_token(client)
    sid = (await describe(client, auth)).json()["session_id"]
    assert (await describe(client, auth)).status_code == 429
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Hair?"})
    assert r.status_code == 200
    assert (await client.delete(f"/v1/session/{sid}", headers=auth)).status_code == 204


async def test_ask_has_its_own_tighter_limit():
    client, _ = make_client(rate_ask_device_per_min=2)
    auth = await get_token(client)
    sid = (await describe(client, auth)).json()["session_id"]
    codes = [
        (await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Hair?"})).status_code
        for _ in range(3)
    ]
    assert codes == [200, 200, 429]


async def test_token_minting_limited_per_ip():
    client, _ = make_client(rate_auth_ip_per_min=3)
    codes = [(await client.post("/v1/auth/anon", json={"device_id": str(uuid.uuid4())})).status_code for _ in range(4)]
    assert codes == [200, 200, 200, 429]


async def test_unauthenticated_requests_get_401_never_429():
    client, _ = make_client(rate_general_ip_per_min=1, rate_describe_ip_per_min=1)
    codes = {(await client.post("/v1/describe", files=files())).status_code for _ in range(5)}
    assert codes == {401}


async def test_health_is_not_rate_limited():
    client, _ = make_client(rate_general_ip_per_min=1)
    codes = [(await client.get("/v1/health")).status_code for _ in range(5)]
    assert codes == [200] * 5


async def test_window_resets_with_the_clock():
    now = [1_000_000.0]
    limiter = RateLimiter(fakeredis.FakeAsyncRedis(), SECRET, clock=lambda: now[0])
    assert await limiter.check("s", "ip", "1.1.1.1", 1) is None
    wait = await limiter.check("s", "ip", "1.1.1.1", 1)
    assert wait is not None and 1 <= wait <= 60
    now[0] += 61
    assert await limiter.check("s", "ip", "1.1.1.1", 1) is None


async def test_redis_keys_hold_no_raw_ip_or_device_id():
    client, redis = make_client()
    device = uuid.uuid4()
    auth = await get_token(client, device)
    await describe(client, auth)
    keys = " ".join(k.decode() for k in await redis.keys("rl:*"))
    assert keys and str(device) not in keys and "127.0.0.1" not in keys


async def test_forwarded_for_ignored_unless_proxy_is_trusted():
    # Spoofed X-Forwarded-For must not let a client dodge the per-IP limit.
    client, _ = make_client(rate_auth_ip_per_min=1)
    codes = []
    for i in range(3):
        r = await client.post(
            "/v1/auth/anon", json={"device_id": str(uuid.uuid4())}, headers={"X-Forwarded-For": f"9.9.9.{i}"}
        )
        codes.append(r.status_code)
    assert codes == [200, 429, 429]


async def test_trusted_proxy_uses_last_forwarded_address():
    client, _ = make_client(rate_auth_ip_per_min=1, trust_proxy_headers=True)

    async def mint(xff):
        return (
            await client.post("/v1/auth/anon", json={"device_id": str(uuid.uuid4())}, headers={"X-Forwarded-For": xff})
        ).status_code

    assert await mint("6.6.6.6, 1.1.1.1") == 200
    assert await mint("7.7.7.7, 1.1.1.1") == 429  # same real client; the spoofed first entry is ignored
    assert await mint("7.7.7.7, 2.2.2.2") == 200


async def test_redis_outage_returns_503_with_spoken_text():
    client, _ = make_client(redis=fakeredis.FakeAsyncRedis(connected=False))
    r = await client.post("/v1/auth/anon", json={"device_id": str(uuid.uuid4())})
    assert r.status_code == 503
    assert r.json()["spoken_text"]


# ---------- daily AI cap ----------


async def test_daily_cap_blocks_after_limit_and_counts_describe_and_ask():
    client, _ = make_client(daily_ai_call_limit=2)
    auth = await get_token(client)
    sid = (await describe(client, auth)).json()["session_id"]  # call 1
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Hair?"})  # call 2
    assert r.status_code == 200
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Hair?"})
    assert r.status_code == 429
    body = r.json()
    assert body["code"] == "daily_limit_reached" and "tomorrow" in body["spoken_text"]
    assert 0 < int(r.headers["retry-after"]) <= 86400


async def test_daily_cap_is_global_across_devices():
    client, _ = make_client(daily_ai_call_limit=1)
    assert (await describe(client, await get_token(client))).status_code == 200
    r = await describe(client, await get_token(client))
    assert r.status_code == 429 and r.json()["code"] == "daily_limit_reached"


async def test_failed_ai_call_gives_the_slot_back():
    client, redis = make_client(provider=FailingProvider(), daily_ai_call_limit=1)
    auth = await get_token(client)
    for _ in range(3):  # would be 429 on try 2 if failures consumed the budget
        assert (await describe(client, auth)).status_code == 502
    assert int(await redis.get(next(iter(await redis.keys("daily_ai:*"))))) == 0


async def test_ask_on_missing_session_does_not_consume_budget():
    client, redis = make_client(daily_ai_call_limit=1)
    auth = await get_token(client)
    r = await client.post("/v1/ask", headers=auth, json={"session_id": str(uuid.uuid4()), "question": "hi"})
    assert r.status_code == 404
    assert await redis.keys("daily_ai:*") == []


async def test_rejected_upload_does_not_consume_budget():
    client, redis = make_client(daily_ai_call_limit=5)
    auth = await get_token(client)
    r = await client.post("/v1/describe", headers=auth, files={"image": ("x.jpg", b"not an image", "image/jpeg")})
    assert r.status_code == 415
    assert await redis.keys("daily_ai:*") == []


async def test_daily_cap_resets_on_a_new_utc_day():
    clock = [dt.datetime(2026, 10, 7, 23, 59, tzinfo=dt.timezone.utc)]
    cap = DailyCap(fakeredis.FakeAsyncRedis(), limit=1, now=lambda: clock[0])
    await cap.reserve()
    with pytest.raises(Exception) as exc:
        await cap.reserve()
    assert exc.value.status_code == 429 and exc.value.headers["Retry-After"] == "60"
    clock[0] += dt.timedelta(minutes=2)
    await cap.reserve()  # new day, new budget


async def test_daily_cap_of_zero_allows_no_calls():
    client, _ = make_client(daily_ai_call_limit=0)
    assert (await describe(client, await get_token(client))).status_code == 429


# ---------- CORS ----------


async def test_no_cors_headers_by_default():
    client, _ = make_client()
    r = await client.options(
        "/v1/describe", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"}
    )
    assert "access-control-allow-origin" not in r.headers


async def test_cors_allows_only_listed_origins():
    client, _ = make_client(cors_origins="https://app.example.com, http://localhost:5173/")
    pre = {"Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "authorization"}
    ok = await client.options("/v1/describe", headers={"Origin": "http://localhost:5173", **pre})
    assert ok.status_code == 200 and ok.headers["access-control-allow-origin"] == "http://localhost:5173"
    assert "authorization" in ok.headers["access-control-allow-headers"].lower()
    assert "access-control-allow-credentials" not in ok.headers
    bad = await client.options("/v1/describe", headers={"Origin": "https://evil.example", **pre})
    assert bad.status_code == 400 and "access-control-allow-origin" not in bad.headers


async def test_cors_exposes_retry_after_on_errors():
    client, _ = make_client(cors_origins="https://app.example.com", rate_describe_device_per_min=1)
    auth = await get_token(client)
    await describe(client, auth)
    r = await client.post(
        "/v1/describe", headers={**auth, "Origin": "https://app.example.com"}, files=files()
    )
    assert r.status_code == 429
    assert r.headers["access-control-allow-origin"] == "https://app.example.com"
    assert "retry-after" in r.headers["access-control-expose-headers"].lower()


def test_wildcard_cors_origin_is_refused_at_startup():
    with pytest.raises(ValueError):
        make_client(cors_origins="*")


# ---------- security headers ----------


async def test_security_headers_on_api_responses():
    client, _ = make_client()
    r = await client.get("/v1/health")
    h = r.headers
    assert h["x-content-type-options"] == "nosniff"
    assert h["x-frame-options"] == "DENY"
    assert h["referrer-policy"] == "no-referrer"
    assert h["cache-control"] == "no-store"
    assert "frame-ancestors 'none'" in h["content-security-policy"]
    assert "geolocation=()" in h["permissions-policy"]
    assert "strict-transport-security" not in h  # plain http


async def test_hsts_only_over_https():
    client, _ = make_client()
    r = await client.get("/v1/health", headers={"X-Forwarded-Proto": "https"})
    assert "max-age=" in r.headers["strict-transport-security"]


async def test_error_responses_also_get_security_headers():
    client, _ = make_client()
    r = await client.post("/v1/describe", files=files())
    assert r.status_code == 401 and r.headers["x-content-type-options"] == "nosniff"


async def test_docs_still_work_and_are_not_blocked_by_csp():
    client, _ = make_client()
    r = await client.get("/docs")
    assert r.status_code == 200
    assert "content-security-policy" not in r.headers and r.headers["x-content-type-options"] == "nosniff"
    assert (await client.get("/openapi.json")).status_code == 200


# ---------- request size ----------


async def test_oversized_content_length_rejected_before_parsing():
    client, _ = make_client(max_image_bytes=1000)
    auth = await get_token(client)
    r = await client.post("/v1/describe", headers=auth, files=files(JPEG + b"\0" * 70_000))
    assert r.status_code == 413 and r.json()["code"] == "image_too_large" and r.json()["spoken_text"]


async def test_chunked_upload_without_content_length_is_also_capped():
    client, _ = make_client(max_image_bytes=1000)
    auth = await get_token(client)

    async def chunks():
        # A valid multipart opening, then ~100 KB of file bytes, sent chunked (no Content-Length).
        crlf = bytes([13, 10])
        yield (
            b"--abc" + crlf
            + b'Content-Disposition: form-data; name="image"; filename="a.jpg"' + crlf
            + b"Content-Type: image/jpeg" + crlf + crlf
        )
        for _ in range(100):
            yield b"x" * 1024

    r = await client.post(
        "/v1/describe", headers={**auth, "Content-Type": "multipart/form-data; boundary=abc"}, content=chunks()
    )
    assert r.status_code == 413


async def test_json_endpoints_have_a_small_body_limit():
    client, _ = make_client()
    auth = await get_token(client)
    r = await client.post(
        "/v1/ask", headers=auth, json={"session_id": str(uuid.uuid4()), "question": "x" * 100_000}
    )
    assert r.status_code == 413


async def test_photo_over_1mb_is_never_spooled_to_disk(monkeypatch):
    """Starlette spools uploads >1 MB to a temp file by default; photos must stay in memory."""

    def forbidden(*a, **k):
        raise AssertionError("an upload touched the disk")

    monkeypatch.setattr(tempfile, "TemporaryFile", forbidden)
    monkeypatch.setattr(tempfile, "NamedTemporaryFile", forbidden)
    client, _ = make_client()
    auth = await get_token(client)
    big = JPEG + b"\0" * (3 * 1024 * 1024)
    r = await client.post("/v1/describe", headers=auth, files=files(big))
    assert r.status_code == 200


# ---------- request IDs and logging ----------


async def test_every_response_has_a_request_id_and_valid_incoming_ids_are_kept():
    client, _ = make_client()
    r = await client.get("/v1/health")
    assert len(r.headers["x-request-id"]) == 32
    r = await client.get("/v1/health", headers={"X-Request-ID": "abc-12345678"})
    assert r.headers["x-request-id"] == "abc-12345678"
    r = await client.get("/v1/health", headers={"X-Request-ID": "bad id\twith junk"})
    assert r.headers["x-request-id"] != "bad id\twith junk"


class ListHandler(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines: list[str] = []
        self.setFormatter(JsonFormatter())

    def emit(self, record):
        self.lines.append(self.format(record))


async def test_logs_have_request_ids_and_no_payload_content():
    client, _ = make_client()
    handler = ListHandler()
    logging.getLogger().addHandler(handler)
    try:
        auth = await get_token(client)
        sid = (await describe(client, auth)).json()["session_id"]
        question = "SECRET-QUESTION-about-my-stain"
        await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": question})
        await client.delete(f"/v1/session/{sid}", headers=auth)
    finally:
        logging.getLogger().removeHandler(handler)

    entries = [json.loads(line) for line in handler.lines]
    requests = [e for e in entries if e["msg"] == "request"]
    assert len(requests) >= 4
    assert all(e["request_id"] != "-" for e in requests)
    routes = {e["route"] for e in requests}
    assert "/session/{session_id}" in routes  # template, never the raw path with the ID
    dump = "\n".join(handler.lines)
    for secret in (question, sid, auth["Authorization"].split()[1], "navy", "collar", "pixels"):
        assert secret not in dump
    assert all(len(e["device"]) == 8 for e in requests if e.get("device"))


def test_json_formatter_drops_exception_messages_but_keeps_type():
    try:
        raise ValueError("contains a private description")
    except ValueError:
        import sys

        rec = logging.LogRecord("x", logging.ERROR, __file__, 1, "failed", None, sys.exc_info())
    out = JsonFormatter().format(rec)
    assert "private description" not in out and json.loads(out)["exc_type"] == "ValueError"
