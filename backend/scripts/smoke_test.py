"""Smoke test for a running VisionMirror API (local or deployed).

    python scripts/smoke_test.py https://visionmirror-api.onrender.com
    python scripts/smoke_test.py http://localhost:8000 --photo ../samples/street_woman.jpg
    python scripts/smoke_test.py https://your-api --skip-ai          # no AI calls, costs nothing
    python scripts/smoke_test.py https://your-api --origin https://your-frontend.app

Exit code 0 = everything passed, 1 = something failed. Uses 2 AI calls (describe + ask) unless --skip-ai.
Needs only httpx and Pillow (already in requirements.txt).
"""
import argparse
import io
import sys
import time
import uuid
from pathlib import Path

import httpx

failures = 0
warnings = 0


def report(ok: bool, name: str, detail: str = "") -> bool:
    global failures
    failures += 0 if ok else 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))
    return ok


def warn(name: str, detail: str = "") -> None:
    global warnings
    warnings += 1
    print(f"  [WARN] {name}" + (f"  ({detail})" if detail else ""))


def sample_jpeg() -> bytes:
    """A plain generated image: enough to exercise the pipeline, but a real AI will say it shows no person."""
    from PIL import Image

    img = Image.linear_gradient("L").resize((640, 480)).convert("RGB")
    buf = io.BytesIO()
    img.save(buf, "JPEG")
    return buf.getvalue()


def wait_for_health(client: httpx.Client, wait_seconds: int) -> bool:
    """Free hosts sleep when idle; the first request can take a minute."""
    deadline = time.time() + wait_seconds
    while True:
        try:
            r = client.get("/v1/health")
            if r.status_code == 200:
                return True
        except httpx.HTTPError:
            pass
        if time.time() > deadline:
            return False
        print("  ...waiting for the service to wake up")
        time.sleep(5)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("base_url")
    ap.add_argument("--photo", help="a real photo of a person (default: a generated image)")
    ap.add_argument("--skip-ai", action="store_true", help="skip describe/ask so no AI calls are made")
    ap.add_argument("--origin", help="a browser origin that should be allowed by CORS")
    ap.add_argument("--wait", type=int, default=120, help="seconds to wait for a sleeping service")
    args = ap.parse_args()

    base = args.base_url.rstrip("/")
    https = base.startswith("https://")
    client = httpx.Client(base_url=base, timeout=httpx.Timeout(90.0, connect=20.0))
    print(f"Smoke test: {base}\n")

    print("Service")
    if not report(wait_for_health(client, args.wait), "GET /v1/health returns 200"):
        print("\nService is not reachable. Check the deploy logs.")
        return 1
    health = client.get("/v1/health")
    report(health.json().get("status") == "ok", "health body says ok")
    if not https and not base.startswith("http://localhost") and not base.startswith("http://127."):
        warn("not using HTTPS", "photos would travel unencrypted")
    docs = client.get("/docs")
    report(docs.status_code == 200, "GET /docs (Swagger UI) works")

    print("\nSecurity headers")
    h = health.headers
    report(h.get("x-content-type-options") == "nosniff", "X-Content-Type-Options: nosniff")
    report(h.get("x-frame-options") == "DENY", "X-Frame-Options: DENY")
    report(h.get("cache-control") == "no-store", "Cache-Control: no-store")
    report(bool(h.get("x-request-id")), "X-Request-ID present")
    if https:
        report("max-age" in h.get("strict-transport-security", ""), "Strict-Transport-Security over HTTPS")

    print("\nAuth")
    r = client.post("/v1/describe", files={"image": ("a.jpg", sample_jpeg(), "image/jpeg")})
    report(r.status_code == 401, "describe without a token is rejected (401)", f"got {r.status_code}")
    r = client.post("/v1/auth/anon", json={"device_id": "not-a-uuid"})
    report(r.status_code == 422, "auth rejects a bad device_id (422)", f"got {r.status_code}")
    r = client.post("/v1/auth/anon", json={"device_id": str(uuid.uuid4())})
    if not report(r.status_code == 200 and "access_token" in r.json(), "POST /v1/auth/anon returns a token"):
        return 1
    auth = {"Authorization": f"Bearer {r.json()['access_token']}"}

    print("\nCORS")
    evil = client.options(
        "/v1/describe",
        headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"},
    )
    report("access-control-allow-origin" not in evil.headers, "unlisted origin gets no CORS permission")
    if args.origin:
        ok = client.options(
            "/v1/describe",
            headers={"Origin": args.origin, "Access-Control-Request-Method": "POST"},
        )
        report(ok.headers.get("access-control-allow-origin") == args.origin, f"{args.origin} is allowed")

    if args.skip_ai:
        print("\nAI flow skipped (--skip-ai)")
    else:
        print("\nAI flow (2 AI calls)")
        photo = Path(args.photo).read_bytes() if args.photo else sample_jpeg()
        t = time.time()
        r = client.post(
            "/v1/describe",
            headers=auth,
            files={"image": ("photo.jpg", photo, "image/jpeg")},
            data={"detail_level": "brief"},
        )
        took = time.time() - t
        if report(r.status_code == 200, "POST /v1/describe returns 200", f"{took:.1f}s, got {r.status_code}"):
            body = r.json()
            needed = {"session_id", "image_quality", "summary", "outfit", "issues", "colour_harmony", "spoken_text", "confidence"}
            report(needed <= body.keys(), "describe response has the documented fields")
            report(bool(body["spoken_text"]), "spoken_text is not empty")
            print(f"        spoken_text: {body['spoken_text'][:140]}")
            sid = body.get("session_id")
            if sid is None:
                warn("photo judged unusable, so no session", "use --photo with a real photo to test ask/delete")
            else:
                r = client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Do my colours go together?"})
                report(r.status_code == 200 and bool(r.json().get("spoken_text")), "POST /v1/ask answers", f"got {r.status_code}")
                r = client.delete(f"/v1/session/{sid}", headers=auth)
                report(r.status_code == 204, "DELETE /v1/session/{id} returns 204", f"got {r.status_code}")
                r = client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "hi"})
                report(r.status_code == 404, "ask after delete returns 404 (photo is really gone)", f"got {r.status_code}")
        elif r.status_code == 429:
            warn("rate limited or daily cap reached", r.json().get("code", ""))

    print(f"\n{'ALL CHECKS PASSED' if failures == 0 else f'{failures} CHECK(S) FAILED'}"
          + (f" ({warnings} warning(s))" if warnings else ""))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
