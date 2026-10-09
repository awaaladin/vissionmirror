"""Publish a release to the download site.

    python build.py --url https://visionmirror-app.vercel.app --apk ../android/app/build/outputs/apk/release/app-release.apk

Copies the APK to downloads/VisionMirror.apk, writes release.json (version, size, SHA-256) which the page
reads, and renders index.html from index.template.html with a QR code for the page address.
Then deploy this folder:  vercel deploy --prod   (run from site/)
"""
import argparse
import datetime
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

import segno

HERE = Path(__file__).resolve().parent
GRADLE = HERE.parent / "android" / "app" / "build.gradle.kts"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="public address of this site, for the QR code")
    ap.add_argument("--apk", required=True)
    args = ap.parse_args()

    apk = Path(args.apk)
    if not apk.exists():
        sys.exit(f"APK not found: {apk}")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', GRADLE.read_text(encoding="utf-8-sig")).group(1)

    (HERE / "downloads").mkdir(exist_ok=True)
    dest = HERE / "downloads" / "VisionMirror.apk"
    shutil.copyfile(apk, dest)
    data = dest.read_bytes()
    release = {
        "version": version,
        "sizeMB": round(len(data) / 1_000_000, 1),
        "sha256": hashlib.sha256(data).hexdigest(),
        "date": datetime.date.today().isoformat(),
    }
    (HERE / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")

    qr = segno.make(args.url.rstrip("/") + "/", error="m")
    svg = qr.svg_inline(scale=4, border=1, dark="#000000", light="#ffffff", omitsize=True, svgclass=None, lineclass=None)
    html = (HERE / "index.template.html").read_text(encoding="utf-8").replace("__QR__", svg)
    (HERE / "index.html").write_text(html, encoding="utf-8")
    print(f"published {version}: {release['sizeMB']} MB, sha256 {release['sha256'][:16]}...")


main()
