from fastapi import APIRouter, Depends, Query, Request

from app.core.errors import APIError
from app.core.rate_limit import limited

router = APIRouter(tags=["speech"])

MAX_AUDIO_BYTES = 4 * 1024 * 1024  # about two minutes of 16 kHz speech; the app sends a few seconds


@router.post("/transcribe")
async def transcribe(
    request: Request,
    language: str = Query("en", pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$"),
    _: None = Depends(limited("speech")),
) -> dict:
    """What was said in a short recording (the audio is the request body, e.g. audio/wav).

    Answers {"text": ..., "english": ...}: the words in the spoken language, and in English. 501 = not available here.
    """
    content_type = request.headers.get("content-type", "")
    if not content_type.startswith("audio/"):
        raise APIError(415, "unsupported_audio", "Send the recording as the request body, with an audio/* content type.", "")
    audio = await request.body()
    if len(audio) < 500:
        raise APIError(422, "audio_too_short", "That recording is empty.", "")
    if len(audio) > MAX_AUDIO_BYTES:
        raise APIError(413, "audio_too_long", "That recording is too long.", "")
    result = await request.app.state.transcriber.transcribe(audio, content_type, language.split("-")[0].lower())
    if result is None:
        raise APIError(501, "transcription_unavailable", "Speech recognition is not available right now.", "")
    return {"text": result.text, "english": result.english}
