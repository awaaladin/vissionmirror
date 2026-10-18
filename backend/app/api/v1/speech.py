from fastapi import APIRouter, Depends, Request, Response

from app.core.errors import APIError
from app.core.rate_limit import limited
from app.schemas.speech import SpeechRequest
from app.services.speech_service import SpeechUnavailable

router = APIRouter(tags=["speech"])


@router.post("/speech", response_class=Response, responses={200: {"content": {"audio/mpeg": {}}}})
async def speech(body: SpeechRequest, request: Request, _: None = Depends(limited("speech"))) -> Response:
    """Audio (MP3) for a piece of text: Nigerian English, or Hausa/Yoruba/Igbo through VoiceVerse. 501 = use the phone's voice."""
    try:
        audio = await request.app.state.speech.synthesize(body.text, body.rate, body.voice, body.language)
    except SpeechUnavailable:
        raise APIError(
            501,  # not 503: the app retries 503s, and 'not configured' will not fix itself
            "speech_unavailable",
            "The cloud voice is not available.",
            "",
        )
    return Response(audio, media_type="audio/mpeg", headers={"Cache-Control": "private, max-age=3600"})
