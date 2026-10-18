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
    ap.add_argument("--apk", help="the APK to publish")
    ap.add_argument("--keep-apk", action="store_true", help="re-render the page only; keep the APK and release.json already here")
    ap.add_argument("--contact-email", default="", help="shown on the Contact section; omit to show a placeholder")
    args = ap.parse_args()

    if args.keep_apk:
        existing = HERE / "release.json"
        if not existing.exists() or not (HERE / "downloads" / "VisionMirror.apk").exists():
            sys.exit("--keep-apk needs an existing downloads/VisionMirror.apk and release.json")
        render(args, None)
        return
    if not args.apk:
        sys.exit("give --apk, or --keep-apk")
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

    render(args, release)
    print(f"published {version}: {release['sizeMB']} MB, sha256 {release['sha256'][:16]}...")


def render(args, release) -> None:
    """Write index.html from the template: the QR code for this address and the contact button."""
    import html as _html

    qr = segno.make(args.url.rstrip("/") + "/", error="m")
    svg = qr.svg_inline(scale=4, border=1, dark="#000000", light="#ffffff", omitsize=True, svgclass=None, lineclass=None)
    email = args.contact_email.strip()
    contact = (
        f'<a class="btn" href="mailto:{_html.escape(email)}?subject=VisionMirror">Email us</a>'
        if email
        else '<p><strong>Contact details are being set up.</strong> Check back soon.</p>'
    )
    page = (HERE / "index.template.html").read_text(encoding="utf-8").replace("__QR__", svg).replace("__CONTACT__", contact)
    (HERE / "index.html").write_text(page, encoding="utf-8")
    if release is None:
        print("re-rendered index.html (APK and release.json unchanged)")


main()
