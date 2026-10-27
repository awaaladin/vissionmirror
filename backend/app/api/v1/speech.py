import hashlib

from fastapi import APIRouter, Depends, Request, Response

from app.core.errors import APIError
from app.core.rate_limit import limited
from app.core.security import current_device
from app.schemas.speech import SpeechRequest
from app.services.speech_service import VOICEVERSE_LANGUAGES, SpeechUnavailable, speech_language

router = APIRouter(tags=["speech"])


@router.post("/speech", response_class=Response, responses={200: {"content": {"audio/mpeg": {}}}})
async def speech(
    body: SpeechRequest, request: Request, subject: str = Depends(current_device), _: None = Depends(limited("speech")),
) -> Response:
    """Audio (MP3) for a piece of text: Nigerian English, or Hausa/Yoruba/Igbo through VoiceVerse. 501 = use the phone's voice."""
    settings, meter, redis = request.app.state.settings, request.app.state.usage, request.app.state.redis
    local = speech_language(body.language) in VOICEVERSE_LANGUAGES
    cache_key = None
    if local:
        # The real local voice is the most expensive thing we buy. The same sentence is never bought twice in a day,
        # and each person has a daily share of it; past that the app uses the phone's own voice (501 below).
        cache_key = "voicecache:" + hashlib.sha256(f"{body.language}|{body.voice}|{body.rate}|{body.text}".encode()).hexdigest()
        try:
            cached = await redis.get(cache_key)
        except Exception:  # noqa: BLE001
            cached = None
        if cached:
            return Response(cached, media_type="audio/mpeg", headers={"Cache-Control": "private, max-age=3600"})
        if await meter.local_voice_used(subject) >= settings.local_voice_daily_pieces:
            raise APIError(501, "speech_unavailable", "Today's share of the local voice is used.", "")
    try:
        audio = await request.app.state.speech.synthesize(body.text, body.rate, body.voice, body.language)
    except SpeechUnavailable:
        raise APIError(
            501,  # not 503: the app retries 503s, and 'not configured' will not fix itself
            "speech_unavailable",
            "The cloud voice is not available.",
            "",
        )
    if local:
        await meter.add_local_voice(subject, len(body.text))
        if len(audio) < 300_000:
            try:
                await redis.set(cache_key, audio, ex=24 * 3600)
            except Exception:  # noqa: BLE001
                pass
    return Response(audio, media_type="audio/mpeg", headers={"Cache-Control": "private, max-age=3600"})
