import asyncio
import uuid

import pytest

from app.ai.mock_provider import MockProvider
from app.ai.provider import Turn
from app.services.session_store import MAX_HISTORY_TURNS, SessionStore

IMAGE = b"\xff\xd8\xff" + b"secret-pixels"


@pytest.fixture
async def analysis():
    return await MockProvider("outfit").describe(
        image=IMAGE, media_type="image/jpeg", detail_level="standard", language="en"
    )


@pytest.fixture
def store(redis):
    return SessionStore(redis, ttl_seconds=600)


async def test_create_sets_ten_minute_ttl_on_both_keys(store, redis, analysis):
    sid = await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    for key in (f"session:{sid}:meta", f"session:{sid}:image"):
        assert 590 < await redis.ttl(key) <= 600


async def test_get_returns_image_and_context(store, analysis):
    sid = await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    session, image = await store.get(sid, "d")
    assert image == IMAGE
    assert session.analysis.summary == analysis.summary


async def test_expired_session_is_gone(store, redis, analysis):
    sid = await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    await redis.pexpire(f"session:{sid}:image", 1)
    await asyncio.sleep(0.05)
    assert await store.get(sid, "d") is None


async def test_wrong_device_cannot_read_or_delete(store, analysis):
    sid = await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    assert await store.get(sid, "someone-else") is None
    assert await store.delete(sid, "someone-else") is False
    assert await store.get(sid, "d") is not None


async def test_delete_removes_image_and_meta(store, redis, analysis):
    sid = await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    assert await store.delete(sid, "d") is True
    assert await redis.keys("*") == []


async def test_missing_session_returns_none(store):
    assert await store.get(uuid.uuid4(), "d") is None
    assert await store.delete(uuid.uuid4(), "d") is False


async def test_append_turns_slides_ttl_and_caps_history(store, redis, analysis):
    sid = await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    await redis.expire(f"session:{sid}:image", 5)
    session, _ = await store.get(sid, "d")
    turns = [Turn(role="user", text=str(i)) for i in range(MAX_HISTORY_TURNS + 6)]
    await store.append_turns(sid, session, turns)
    assert await redis.ttl(f"session:{sid}:image") > 500
    session, _ = await store.get(sid, "d")
    assert len(session.history) == MAX_HISTORY_TURNS
    assert session.history[-1].text == str(MAX_HISTORY_TURNS + 5)


async def test_image_is_never_written_to_disk(store, analysis, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    await store.create(device_id="d", image=IMAGE, media_type="image/jpeg", analysis=analysis)
    assert list(tmp_path.iterdir()) == []
