import uuid

from pydantic import BaseModel
from redis.asyncio import Redis

from app.ai.provider import Turn
from app.schemas.describe import DescribeAnalysis

MAX_HISTORY_TURNS = 20


class Session(BaseModel):
    device_id: str
    media_type: str
    analysis: DescribeAnalysis
    history: list[Turn] = []


class SessionStore:
    """Holds a photo and its conversation in Redis only, with a TTL. Nothing touches disk."""

    def __init__(self, redis: Redis, ttl_seconds: int):
        self.redis = redis
        self.ttl = ttl_seconds

    @staticmethod
    def _keys(session_id: uuid.UUID) -> tuple[str, str]:
        return f"session:{session_id}:meta", f"session:{session_id}:image"

    async def create(
        self, *, device_id: str, image: bytes, media_type: str, analysis: DescribeAnalysis
    ) -> uuid.UUID:
        session_id = uuid.uuid4()
        meta_key, image_key = self._keys(session_id)
        session = Session(device_id=device_id, media_type=media_type, analysis=analysis)
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.set(meta_key, session.model_dump_json(), ex=self.ttl)
            pipe.set(image_key, image, ex=self.ttl)
            await pipe.execute()
        return session_id

    async def get(self, session_id: uuid.UUID, device_id: str) -> tuple[Session, bytes] | None:
        """Return the session only if it exists and belongs to this device."""
        meta_key, image_key = self._keys(session_id)
        meta, image = await self.redis.mget(meta_key, image_key)
        if meta is None or image is None:
            return None
        session = Session.model_validate_json(meta)
        if session.device_id != device_id:
            return None
        return session, image

    async def append_turns(self, session_id: uuid.UUID, session: Session, turns: list[Turn]) -> None:
        """Save new turns and slide the TTL forward so a live conversation isn't cut off."""
        meta_key, image_key = self._keys(session_id)
        session.history = (session.history + turns)[-MAX_HISTORY_TURNS:]
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.set(meta_key, session.model_dump_json(), ex=self.ttl)
            pipe.expire(image_key, self.ttl)
            await pipe.execute()

    async def delete(self, session_id: uuid.UUID, device_id: str) -> bool:
        if await self.get(session_id, device_id) is None:
            return False
        await self.redis.delete(*self._keys(session_id))
        return True
