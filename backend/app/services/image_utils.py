import io

from fastapi import UploadFile
from PIL import Image, ImageOps, UnidentifiedImageError

from app.core.errors import APIError

_CHUNK = 64 * 1024

# Longest side sent to the model. Larger photos cost more tokens and add no useful detail.
MAX_SIDE = 1568
# Refuse decompression bombs: a small file that expands to a huge bitmap.
MAX_PIXELS = 50_000_000
JPEG_QUALITY = 88

Image.MAX_IMAGE_PIXELS = MAX_PIXELS


def sniff_media_type(data: bytes) -> str | None:
    """Identify JPEG/PNG/WebP from magic bytes; never trust the client's Content-Type."""
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def _unreadable() -> APIError:
    return APIError(
        415,
        "unsupported_image",
        "Image must be a valid JPEG, PNG or WebP.",
        "I couldn't read that photo. Please try taking it again.",
    )


def normalize_image(data: bytes) -> tuple[bytes, str]:
    """Make a real-world photo safe and cheap to analyse. Entirely in memory.

    - Applies the EXIF orientation (phone photos are often stored sideways) so the model sees her upright.
    - Downsizes to MAX_SIDE and re-encodes as JPEG, which also strips EXIF/GPS metadata.
    - Rejects corrupt files and decompression bombs.
    """
    try:
        with Image.open(io.BytesIO(data)) as img:
            img.load()
            img = ImageOps.exif_transpose(img)
            if img.mode != "RGB":
                # Flatten transparency onto white rather than black.
                rgba = img.convert("RGBA")
                background = Image.new("RGB", rgba.size, (255, 255, 255))
                background.paste(rgba, mask=rgba.getchannel("A"))
                img = background
            img.thumbnail((MAX_SIDE, MAX_SIDE), Image.Resampling.LANCZOS)
            out = io.BytesIO()
            img.save(out, "JPEG", quality=JPEG_QUALITY, optimize=True)
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise _unreadable()
    return out.getvalue(), "image/jpeg"


async def read_image(upload: UploadFile, max_bytes: int) -> tuple[bytes, str]:
    """Read an upload into memory (stopping once it exceeds max_bytes), validate it, and normalise it."""
    buf = bytearray()
    while chunk := await upload.read(_CHUNK):
        buf.extend(chunk)
        if len(buf) > max_bytes:
            raise APIError(
                413,
                "image_too_large",
                f"Image exceeds {max_bytes} bytes.",
                "That photo is too big. Please try again.",
            )
    data = bytes(buf)
    if sniff_media_type(data) is None:
        raise _unreadable()
    return normalize_image(data)
