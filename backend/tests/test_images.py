import io
from pathlib import Path

import pytest
from PIL import Image

from app.core.errors import APIError
from app.services.image_utils import MAX_SIDE, normalize_image
from tests.conftest import JPEG, make_image

SAMPLE = Path(__file__).resolve().parents[2] / "samples" / "woman_dress.jpg"


def open_img(data: bytes) -> Image.Image:
    return Image.open(io.BytesIO(data))


def test_exif_rotation_is_applied():
    """Phones store portrait photos sideways plus an Orientation tag; the model must see them upright."""
    img = Image.new("RGB", (200, 100), (255, 0, 0))  # stored landscape
    exif = Image.Exif()
    exif[0x0112] = 6  # "rotate 90 degrees clockwise to display"
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif)
    out, media_type = normalize_image(buf.getvalue())
    assert media_type == "image/jpeg"
    assert open_img(out).size == (100, 200)


def test_metadata_including_gps_is_stripped():
    exif = Image.Exif()
    exif[0x010F] = "SecretPhoneMaker"
    buf = io.BytesIO()
    Image.new("RGB", (50, 50)).save(buf, "JPEG", exif=exif)
    assert b"SecretPhoneMaker" in buf.getvalue()
    out, _ = normalize_image(buf.getvalue())
    assert b"SecretPhoneMaker" not in out
    assert not open_img(out).getexif()


def test_large_photo_is_downscaled_keeping_aspect_ratio():
    out, _ = normalize_image(make_image("JPEG", size=(4000, 3000)))
    w, h = open_img(out).size
    assert max(w, h) == MAX_SIDE
    assert abs(w / h - 4 / 3) < 0.01


def test_small_photo_is_not_upscaled():
    out, _ = normalize_image(make_image("JPEG", size=(300, 200)))
    assert open_img(out).size == (300, 200)


@pytest.mark.parametrize("fmt", ["PNG", "WEBP", "JPEG"])
def test_every_format_comes_out_as_jpeg(fmt):
    out, media_type = normalize_image(make_image(fmt))
    assert media_type == "image/jpeg" and out.startswith(b"\xff\xd8\xff")


def test_transparent_png_is_flattened_onto_white():
    buf = io.BytesIO()
    Image.new("RGBA", (20, 20), (0, 0, 0, 0)).save(buf, "PNG")
    out, _ = normalize_image(buf.getvalue())
    r, g, b = open_img(out).getpixel((5, 5))
    assert min(r, g, b) > 240


@pytest.mark.parametrize("bad", [b"\xff\xd8\xff\xe0garbage", JPEG[:40], b"\x89PNG\r\n\x1a\nnope"])
def test_corrupt_images_are_rejected(bad):
    with pytest.raises(APIError) as exc:
        normalize_image(bad)
    assert exc.value.status_code == 415 and exc.value.spoken_text


def test_decompression_bomb_is_rejected(monkeypatch):
    monkeypatch.setattr(Image, "MAX_IMAGE_PIXELS", 1000)
    with pytest.raises(APIError):
        normalize_image(make_image("PNG", size=(100, 100)))


@pytest.mark.skipif(not SAMPLE.exists(), reason="sample photo not downloaded")
async def test_real_photo_goes_through_the_whole_api(client, auth, redis):
    raw = SAMPLE.read_bytes()
    r = await client.post("/v1/describe", headers=auth, files={"image": ("w.jpg", raw, "image/jpeg")})
    assert r.status_code == 200
    stored = await redis.get(f"session:{r.json()['session_id']}:image")
    assert stored and stored.startswith(b"\xff\xd8\xff")
    assert max(open_img(stored).size) <= MAX_SIDE
