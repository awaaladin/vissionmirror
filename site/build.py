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
    ap.add_argument("--apk", help="the APK to publish (64-bit phones, or the only one)")
    ap.add_argument("--apk32", help="a second APK for older 32-bit phones")
    ap.add_argument("--keep-apk", action="store_true", help="re-render the page only; keep the APK and release.json already here")
    ap.add_argument("--contact-email", default="", help="shown on the Contact section; omit to show a placeholder")
    args = ap.parse_args()

    if args.keep_apk:
        existing = HERE / "release.json"
        if not existing.exists():
            sys.exit("--keep-apk needs an existing release.json")
        render(args, None)
        return
    if not args.apk:
        sys.exit("give --apk, or --keep-apk")
    apk = Path(args.apk)
    if not apk.exists():
        sys.exit(f"APK not found: {apk}")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', GRADLE.read_text(encoding="utf-8-sig")).group(1)

    (HERE / "downloads").mkdir(exist_ok=True)
    for old in (HERE / "downloads").glob("*.apk"):
        old.unlink()

    def publish(src: Path, name: str) -> dict:
        dest = HERE / "downloads" / name
        shutil.copyfile(src, dest)
        data = dest.read_bytes()
        return {"file": f"downloads/{name}", "sizeMB": round(len(data) / 1_000_000, 1), "sha256": hashlib.sha256(data).hexdigest()}

    if args.apk32:
        if not Path(args.apk32).exists():
            sys.exit(f"APK not found: {args.apk32}")
        main64 = publish(apk, "VisionMirror-64bit.apk")
        main32 = publish(Path(args.apk32), "VisionMirror-32bit.apk")
        release = {"version": version, **main64, "date": datetime.date.today().isoformat(), "alt32": main32}
    else:
        main = publish(apk, "VisionMirror.apk")
        release = {"version": version, **main, "date": datetime.date.today().isoformat()}
    (HERE / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")

    render(args, release)
    print(f"published {version}: {release['sizeMB']} MB, sha256 {release['sha256'][:16]}...")


def render_commands() -> str:
    """The "What you can say" section, from the same catalog the app's parser uses (shared/voice-commands.json)."""
    import html as h

    data = json.loads((HERE.parent / "shared" / "voice-commands.json").read_text(encoding="utf-8"))
    out = []
    for sec in data["sections"]:
        cmds = [c for c in data["commands"] if c["section"] == sec["id"]]
        if not cmds:
            continue
        out.append(f'<div class="sec"><h3>{h.escape(sec["title"])}</h3><p class="sec-intro">{h.escape(sec["intro"])}</p>')
        for c in cmds:
            chips = "".join(f"<b>&ldquo;{h.escape(e)}&rdquo;</b>" for e in c["examples"])
            phrases = [p for p in c["phrases"]]
            more = " ".join(f"<i>{h.escape(p)}</i> &middot;" for p in phrases)
            note = f'<p class="cmd-note">{h.escape(c["note"])}</p>' if c["note"] else ""
            screens = f'<p class="cmd-note">Only on the {h.escape(", ".join(c["screens"]))} screen.</p>' if c["screens"] else ""
            verbs = ""
            if c["verbs"]:
                verbs = '<p class="more"><strong>Start with any of:</strong> ' + ", ".join(h.escape(v) for v in c["verbs"][:30]) + "</p>"
            out.append(
                f'<details><summary><span>{h.escape(c["title"])}<small class="eg">&ldquo;{h.escape(c["examples"][0])}&rdquo;</small></span></summary><div class="body">'
                f'<div class="chips">{chips}</div>{note}{screens}'
                f'<p class="more"><strong>{len(phrases)} ways to say it:</strong> {more}</p>{verbs}</div></details>'
            )
        out.append("</div>")
    return "\n".join(out)


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
    page = (HERE / "index.template.html").read_text(encoding="utf-8").replace("__QR__", svg).replace("__CONTACT__", contact).replace("__COMMANDS__", render_commands())
    (HERE / "index.html").write_text(page, encoding="utf-8")
    if release is None:
        print("re-rendered index.html (APK and release.json unchanged)")


main()
