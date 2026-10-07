import time
import uuid

import jwt
import pytest

from app.core.security import ISSUER, create_token, decode_token
from tests.conftest import JPEG


def test_token_round_trip_is_bound_to_device(settings):
    device = uuid.uuid4()
    token, expires_in = create_token(device, settings)
    assert expires_in == 24 * 3600
    assert decode_token(token, settings) == str(device)


def test_expired_token_rejected(settings):
    now = int(time.time())
    token = jwt.encode(
        {"iss": ISSUER, "sub": str(uuid.uuid4()), "iat": now - 100, "exp": now - 10},
        settings.secret_key,
        algorithm="HS256",
    )
    with pytest.raises(jwt.ExpiredSignatureError):
        decode_token(token, settings)


def test_token_signed_with_other_key_rejected(settings):
    token = jwt.encode(
        {"iss": ISSUER, "sub": "x", "exp": int(time.time()) + 100},
        "some-other-key-some-other-key-some-other",
        algorithm="HS256",
    )
    with pytest.raises(jwt.InvalidSignatureError):
        decode_token(token, settings)


async def test_anon_endpoint_returns_bearer_token(client):
    r = await client.post("/v1/auth/anon", json={"device_id": str(uuid.uuid4())})
    assert r.status_code == 200
    body = r.json()
    assert body["token_type"] == "bearer"
    assert body["expires_in"] == 24 * 3600
    assert body["access_token"]


async def test_anon_rejects_non_uuid_device_id(client):
    r = await client.post("/v1/auth/anon", json={"device_id": "hello"})
    assert r.status_code == 422


@pytest.mark.parametrize(
    "method,path",
    [("post", "/v1/describe"), ("post", "/v1/ask"), ("delete", f"/v1/session/{uuid.uuid4()}")],
)
async def test_protected_endpoints_need_a_token(client, method, path):
    r = await getattr(client, method)(path)
    assert r.status_code == 401
    assert r.json()["spoken_text"]
    assert r.headers["www-authenticate"] == "Bearer"


async def test_garbage_token_rejected(client):
    r = await client.post(
        "/v1/describe",
        headers={"Authorization": "Bearer not.a.token"},
        files={"image": ("a.jpg", JPEG, "image/jpeg")},
    )
    assert r.status_code == 401


async def test_health_needs_no_auth(client):
    r = await client.get("/v1/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
