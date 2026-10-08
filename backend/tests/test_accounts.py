import json
import logging
import time
import uuid

import fakeredis
import httpx
import jwt
import pytest

from app.ai.mock_provider import MockProvider
from app.core import passwords
from app.core.config import Settings
from app.core.logging import JsonFormatter
from app.main import create_app
from app.services.user_store import MemoryUserStore
from tests.conftest import JPEG, get_token

SECRET = "test-secret-key-that-is-at-least-32-characters"
GOOD = {"email": "Ada@Example.com", "password": "correct horse battery", "display_name": "Ada"}


def make(users="memory", **overrides):
    settings = Settings(secret_key=SECRET, _env_file=None, **overrides)
    store = MemoryUserStore() if users == "memory" else None
    app = create_app(settings=settings, redis=fakeredis.FakeAsyncRedis(), provider=MockProvider("outfit"), users=store)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test"), store


async def register(client, **body):
    return await client.post("/v1/auth/register", json={**GOOD, **body})


async def signed_in(client, **body):
    r = await register(client, **body)
    assert r.status_code == 201, r.text
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


# ---------- registering ----------


async def test_register_returns_a_long_lived_user_token_and_the_user():
    client, _ = make()
    r = await register(client)
    assert r.status_code == 201
    body = r.json()
    assert body["user"]["email"] == "ada@example.com"  # normalised
    assert body["user"]["display_name"] == "Ada"
    assert "password" not in json.dumps(body)
    claims = jwt.decode(body["access_token"], SECRET, algorithms=["HS256"], issuer="visionmirror")
    assert claims["typ"] == "user" and claims["sub"] == body["user"]["id"]
    assert 29 * 86400 < body["expires_in"] <= 30 * 86400


async def test_password_is_stored_as_an_argon2_hash_never_plain():
    client, store = make()
    await register(client)
    user, stored = await store.find_by_email("ada@example.com")
    assert stored.startswith("$argon2id$") and GOOD["password"] not in stored


async def test_duplicate_email_is_refused_ignoring_case():
    client, _ = make()
    await register(client)
    r = await register(client, email="ADA@example.COM")
    assert r.status_code == 409
    assert r.json()["code"] == "email_taken" and "signing in" in r.json()["spoken_text"]


@pytest.mark.parametrize(
    "body",
    [
        {"email": "not-an-email"},
        {"email": ""},
        {"password": "short"},
        {"password": "x" * 129},
        {"email": "same@example.com", "password": "same@example.com"},
    ],
)
async def test_bad_input_gets_a_speakable_400(body):
    client, _ = make()
    r = await register(client, **body)
    assert r.status_code == 400
    assert r.json()["code"] == "invalid_input" and r.json()["spoken_text"]


async def test_display_name_is_trimmed_and_optional():
    client, _ = make()
    r = await register(client, display_name="   ")
    assert r.json()["user"]["display_name"] is None
    r = await register(client, email="b@example.com", display_name="n" * 200)
    assert len(r.json()["user"]["display_name"]) == 60


# ---------- signing in ----------


async def test_login_with_correct_password_works_case_insensitively():
    client, _ = make()
    await register(client)
    r = await client.post("/v1/auth/login", json={"email": " ADA@example.com ", "password": GOOD["password"]})
    assert r.status_code == 200 and r.json()["user"]["email"] == "ada@example.com"


async def test_wrong_password_and_unknown_email_look_identical():
    client, _ = make()
    await register(client)
    wrong = await client.post("/v1/auth/login", json={"email": GOOD["email"], "password": "nope nope nope"})
    unknown = await client.post("/v1/auth/login", json={"email": "who@example.com", "password": "nope nope nope"})
    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json() == unknown.json()
    assert wrong.json()["code"] == "invalid_credentials"


async def test_repeated_wrong_passwords_lock_the_email_even_for_the_right_password():
    client, _ = make(login_max_failures=3, rate_account_ip_per_min=100)
    await register(client)
    bad = {"email": GOOD["email"], "password": "wrong wrong wrong"}
    assert [(await client.post("/v1/auth/login", json=bad)).status_code for _ in range(3)] == [401, 401, 401]
    locked = await client.post("/v1/auth/login", json={"email": GOOD["email"], "password": GOOD["password"]})
    assert locked.status_code == 429
    assert int(locked.headers["retry-after"]) > 0 and "wait" in locked.json()["spoken_text"]


async def test_a_successful_login_clears_earlier_failures():
    client, _ = make(login_max_failures=3, rate_account_ip_per_min=100)
    await register(client)
    bad = {"email": GOOD["email"], "password": "wrong wrong wrong"}
    good = {"email": GOOD["email"], "password": GOOD["password"]}
    for _ in range(2):
        await client.post("/v1/auth/login", json=bad)
    assert (await client.post("/v1/auth/login", json=good)).status_code == 200
    for _ in range(2):  # would have hit the limit if the earlier two still counted
        assert (await client.post("/v1/auth/login", json=bad)).status_code == 401


async def test_account_endpoints_are_rate_limited_per_ip():
    client, _ = make(rate_account_ip_per_min=3)
    codes = [(await client.post("/v1/auth/login", json={"email": "a@b.co", "password": "x"})).status_code for _ in range(4)]
    assert codes == [401, 401, 401, 429]


def test_password_verification_helpers():
    h = passwords.hash_password("a good password")
    assert passwords.verify_password("a good password", h)
    assert not passwords.verify_password("another password", h)
    assert not passwords.verify_password("anything", None)  # unknown account: same work, always false
    assert not passwords.verify_password("anything", "not-a-hash")


# ---------- using the app while signed in ----------


async def test_signed_in_user_can_describe_ask_and_delete_and_sessions_are_theirs_alone():
    client, _ = make()
    me = await signed_in(client)
    r = await client.post("/v1/describe", headers=me, files={"image": ("a.jpg", JPEG, "image/jpeg")})
    assert r.status_code == 200
    sid = r.json()["session_id"]
    other = await signed_in(client, email="grace@example.com")
    stranger = await client.post("/v1/ask", headers=other, json={"session_id": sid, "question": "hi"})
    assert stranger.status_code == 404
    assert (await client.post("/v1/ask", headers=me, json={"session_id": sid, "question": "hair?"})).status_code == 200
    assert (await client.delete(f"/v1/session/{sid}", headers=me)).status_code == 204


async def test_me_returns_the_account_and_a_guest_token_is_not_enough():
    client, _ = make()
    me = await signed_in(client)
    r = await client.get("/v1/auth/me", headers=me)
    assert r.status_code == 200 and r.json()["email"] == "ada@example.com"
    guest = await get_token(client)
    r = await client.get("/v1/auth/me", headers=guest)
    assert r.status_code == 401 and r.json()["code"] == "sign_in_required"
    assert (await client.get("/v1/auth/me")).status_code == 401


async def test_deleting_the_account_removes_it_and_kills_its_token_immediately():
    client, store = make()
    me = await signed_in(client)
    assert (await client.delete("/v1/auth/me", headers=me)).status_code == 204
    assert await store.find_by_email("ada@example.com") is None
    # The 30-day token is still cryptographically valid, but must no longer work anywhere.
    assert (await client.get("/v1/auth/me", headers=me)).status_code == 401
    r = await client.post("/v1/describe", headers=me, files={"image": ("a.jpg", JPEG, "image/jpeg")})
    assert r.status_code == 401
    assert (await client.post("/v1/auth/login", json={"email": GOOD["email"], "password": GOOD["password"]})).status_code == 401
    # The email can be used to sign up again.
    assert (await register(client)).status_code == 201


async def test_guest_tokens_are_unchanged_and_marked_as_anon():
    client, _ = make()
    r = await client.post("/v1/auth/anon", json={"device_id": str(uuid.uuid4())})
    claims = jwt.decode(r.json()["access_token"], SECRET, algorithms=["HS256"], issuer="visionmirror")
    assert claims["typ"] == "anon" and r.json()["expires_in"] == 86400


async def test_old_tokens_without_a_type_still_work_as_guest_tokens():
    client, _ = make()
    now = int(time.time())
    token = jwt.encode({"iss": "visionmirror", "sub": str(uuid.uuid4()), "iat": now, "exp": now + 600}, SECRET, algorithm="HS256")
    r = await client.post(
        "/v1/describe", headers={"Authorization": f"Bearer {token}"}, files={"image": ("a.jpg", JPEG, "image/jpeg")}
    )
    assert r.status_code == 200


# ---------- accounts off ----------


async def test_without_a_database_accounts_say_so_but_guest_mode_still_works():
    client, _ = make(users=None)
    r = await register(client)
    assert r.status_code == 503 and r.json()["code"] == "accounts_unavailable"
    assert "guest" in r.json()["spoken_text"]
    guest = await get_token(client)
    r = await client.post("/v1/describe", headers=guest, files={"image": ("a.jpg", JPEG, "image/jpeg")})
    assert r.status_code == 200


# ---------- privacy ----------


class ListHandler(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines: list[str] = []
        self.setFormatter(JsonFormatter())

    def emit(self, record):
        self.lines.append(self.format(record))


async def test_logs_never_contain_emails_names_or_passwords():
    client, _ = make(login_max_failures=2, rate_account_ip_per_min=100)
    handler = ListHandler()
    logging.getLogger().addHandler(handler)
    try:
        await register(client)
        await client.post("/v1/auth/login", json={"email": GOOD["email"], "password": "SECRET-guess-123"})
        await client.post("/v1/auth/login", json={"email": GOOD["email"], "password": "SECRET-guess-456"})
        await client.post("/v1/auth/login", json={"email": GOOD["email"], "password": GOOD["password"]})
    finally:
        logging.getLogger().removeHandler(handler)
    dump = "\n".join(handler.lines).lower()
    assert "ada@example.com" not in dump and GOOD["password"] not in dump and "secret-guess" not in dump


def test_neon_style_connection_strings_are_cleaned_for_asyncpg():
    from app.services.user_store import PostgresUserStore, normalize_dsn

    url, ssl = normalize_dsn(
        "postgresql://u:p@host.neon.tech/multidb?sslmode=require&channel_binding=require&options=-c%20search_path%3Dsplitstay"
    )
    assert url == "postgresql://u:p@host.neon.tech/multidb" and ssl == "require"
    assert normalize_dsn("postgresql://u:p@localhost/db") == ("postgresql://u:p@localhost/db", None)
    # Remote databases are encrypted even when the URL says nothing (Supabase's pooler gives a bare URL).
    assert normalize_dsn("postgresql://u:p@aws-0.pooler.supabase.com:6543/postgres")[1] == "require"
    assert normalize_dsn("postgresql://u:p@db.example.com/x?sslmode=disable")[1] is None
    assert PostgresUserStore("postgresql://u:p@h/d?sslmode=require").ssl == "require"
