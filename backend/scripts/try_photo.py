"""Run real photos through the configured live provider (Gemini or Anthropic) and print what comes back.

Usage (from backend/, with the provider's API key set in .env or the environment):
    python scripts/try_photo.py ../samples/woman_dress.jpg [more.jpg ...] [--detail brief|standard|detailed] [--ask "Is my dress clean?"]

Costs real money (one API call per photo, plus one per --ask). Images are read from disk
by this dev script only; the API itself never writes photos to disk.
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.ai.provider import build_provider  # noqa: E402
from app.core.config import Settings  # noqa: E402
from app.services.image_utils import normalize_image  # noqa: E402


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("photos", nargs="+")
    ap.add_argument("--detail", default="standard", choices=["brief", "standard", "detailed"])
    ap.add_argument("--ask", help="follow-up question to ask about each photo")
    args = ap.parse_args()

    s = Settings(secret_key="x" * 32)
    if s.vision_provider == "mock":
        sys.exit("Set VISION_PROVIDER=gemini or anthropic (and its API key) in backend/.env first.")
    provider = build_provider(s)  # raises a clear error if the key is missing
    print(f"provider={s.vision_provider} model={getattr(provider, 'model', '?')}\n")

    for path in args.photos:
        raw = Path(path).read_bytes()
        data, media_type = normalize_image(raw)  # same preprocessing as the API
        print("=" * 70, f"\n{path} ({len(raw)} bytes -> {len(data)} bytes sent)")
        result = await provider.describe(image=data, media_type=media_type, detail_level=args.detail, language="en")
        print(json.dumps(result.model_dump(), indent=2, ensure_ascii=False))
        print("\nSPOKEN:", result.spoken_text)
        if args.ask and result.image_quality.usable:
            ans = await provider.ask(
                image=data, media_type=media_type, analysis=result, history=[], question=args.ask, language="en"
            )
            print(f"\nQ: {args.ask}\nA: {ans.spoken_text}")


asyncio.run(main())
