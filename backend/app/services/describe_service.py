import uuid

from app.ai.provider import ProviderError, Turn, VisionProvider
from app.core.rate_limit import DailyCap
from app.schemas.ask import AskRequest, AskResponse
from app.schemas.common import DetailLevel
from app.schemas.describe import DescribeResponse
from app.services.session_store import SessionStore
from app.services.translation_service import FALLBACK_NOTICE, NoTranslator, Translator, translation_target


class SessionNotFound(Exception):
    pass


class DescribeService:
    def __init__(
        self, provider: VisionProvider, store: SessionStore, cap: DailyCap, translator: Translator | None = None
    ):
        self.provider = provider
        self.store = store
        self.cap = cap
        self.translator = translator or NoTranslator()

    async def _localise(self, target: str, *texts: str) -> list[str]:
        """Translate each text, or return all of them in English with a spoken notice up front."""
        out = [await self.translator.translate(t, target) for t in texts]
        if any(o is None for o in out):
            return [f"{FALLBACK_NOTICE} {texts[0]}", *texts[1:]]
        return [o for o in out if o is not None]

    async def _with_cap(self, make_call):
        """Reserve one daily AI call; give it back if the AI call fails."""
        await self.cap.reserve()
        try:
            return await make_call()
        except ProviderError:
            await self.cap.release()
            raise

    async def describe(
        self, *, device_id: str, image: bytes, media_type: str, detail_level: DetailLevel, language: str,
        mode: str = "mirror", assistant: str = "Adanna",
    ) -> DescribeResponse:
        target = translation_target(language)
        analysis = await self._with_cap(
            lambda: self.provider.describe(
                image=image, media_type=media_type, detail_level=detail_level,
                language="en" if target else language, mode=mode, assistant=assistant,
            )
        )
        session_id: uuid.UUID | None = None
        if analysis.image_quality.usable:
            session_id = await self.store.create(
                device_id=device_id, image=image, media_type=media_type, analysis=analysis
            )
        shown = analysis.model_copy(deep=True)  # the stored session keeps the English the model wrote
        if target:
            # Unusable photos repeat the advice as spoken_text, so one translation serves both.
            (shown.spoken_text,) = await self._localise(target, analysis.spoken_text)
            if shown.image_quality.advice:
                if shown.image_quality.advice == analysis.spoken_text:
                    shown.image_quality.advice = shown.spoken_text
                else:
                    (shown.image_quality.advice,) = await self._localise(target, shown.image_quality.advice)
        return DescribeResponse(session_id=session_id, **shown.model_dump())

    async def ask(self, *, device_id: str, req: AskRequest) -> AskResponse:
        found = await self.store.get(req.session_id, device_id)
        if found is None:
            raise SessionNotFound
        session, image = found
        target = translation_target(req.language)
        answer = await self._with_cap(
            lambda: self.provider.ask(
                image=image,
                media_type=session.media_type,
                analysis=session.analysis,
                history=session.history,
                question=req.question,
                language="en" if target else req.language,
                assistant=req.assistant,
            )
        )
        await self.store.append_turns(
            req.session_id,
            session,
            [Turn(role="user", text=req.question), Turn(role="assistant", text=answer.answer_text)],
        )
        if not target:
            return answer
        # The model answered in English; the stored history keeps that, and only what the user hears is translated.
        shown = answer.model_copy()
        if answer.spoken_text == answer.answer_text:
            (shown.spoken_text,) = await self._localise(target, answer.spoken_text)
            shown.answer_text = shown.spoken_text
        else:
            shown.answer_text, shown.spoken_text = await self._localise(target, answer.answer_text, answer.spoken_text)
        return shown
