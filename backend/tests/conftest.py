import io
import os
import uuid

os.environ.setdefault("SECRET_KEY", "test-secret-key-that-is-at-least-32-characters")

import fakeredis
import httpx
import pytest
from PIL import Image

from app.ai.mock_provider import MockProvider
from app.core.config import Settings
from app.main import create_app

def make_image(fmt: str = "JPEG", size=(64, 48), color=(120, 60, 200)) -> bytes:
    """A real, decodable image (the API decodes and re-encodes every upload)."""
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, fmt)
    return buf.getvalue()


JPEG = make_image("JPEG")
PNG = make_image("PNG")
WEBP = make_image("WEBP")


@pytest.fixture
def settings() -> Settings:
    # _env_file=None: tests must never read the real .env (it holds the live database URL and API keys).
    return Settings(secret_key="test-secret-key-that-is-at-least-32-characters", _env_file=None)


@pytest.fixture
def redis():
    return fakeredis.FakeAsyncRedis()


@pytest.fixture
def make_client(settings, redis):
    """Build a client with a chosen mock scenario (in-memory transport, nothing to close)."""

    def _make(scenario: str | None = "outfit") -> httpx.AsyncClient:
        app = create_app(settings=settings, redis=redis, provider=MockProvider(scenario))
        return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")

    return _make


@pytest.fixture
async def client(make_client):
    c = make_client("outfit")
    yield c
    await c.aclose()


async def get_token(client: httpx.AsyncClient, device_id: uuid.UUID | None = None) -> dict:
    r = await client.post("/v1/auth/anon", json={"device_id": str(device_id or uuid.uuid4())})
    assert r.status_code == 200
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


@pytest.fixture
async def auth(client):
    return await get_token(client)
