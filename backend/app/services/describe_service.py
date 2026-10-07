import uuid

from app.ai.provider import ProviderError, Turn, VisionProvider
from app.core.rate_limit import DailyCap
from app.schemas.ask import AskRequest, AskResponse
from app.schemas.common import DetailLevel
from app.schemas.describe import DescribeResponse
from app.services.session_store import SessionStore


class SessionNotFound(Exception):
    pass


class DescribeService:
    def __init__(self, provider: VisionProvider, store: SessionStore, cap: DailyCap):
        self.provider = provider
        self.store = store
        self.cap = cap

    async def _with_cap(self, make_call):
        """Reserve one daily AI call; give it back if the AI call fails."""
        await self.cap.reserve()
        try:
            return await make_call()
        except ProviderError:
            await self.cap.release()
            raise

    async def describe(
        self, *, device_id: str, image: bytes, media_type: str, detail_level: DetailLevel, language: str
    ) -> DescribeResponse:
        analysis = await self._with_cap(
            lambda: self.provider.describe(
                image=image, media_type=media_type, detail_level=detail_level, language=language
            )
        )
        session_id: uuid.UUID | None = None
        if analysis.image_quality.usable:
            session_id = await self.store.create(
                device_id=device_id, image=image, media_type=media_type, analysis=analysis
            )
        return DescribeResponse(session_id=session_id, **analysis.model_dump())

    async def ask(self, *, device_id: str, req: AskRequest) -> AskResponse:
        found = await self.store.get(req.session_id, device_id)
        if found is None:
            raise SessionNotFound
        session, image = found
        answer = await self._with_cap(
            lambda: self.provider.ask(
                image=image,
                media_type=session.media_type,
                analysis=session.analysis,
                history=session.history,
                question=req.question,
                language=req.language,
            )
        )
        await self.store.append_turns(
            req.session_id,
            session,
            [Turn(role="user", text=req.question), Turn(role="assistant", text=answer.answer_text)],
        )
        return answer
