"""Pure-ASGI middleware: request IDs + logging, security headers, request size limits."""

import json
import logging
import re
import time
import uuid

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import request_id_var
from app.schemas.common import ErrorResponse

log = logging.getLogger("visionmirror.request")

_REQUEST_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")
_DOC_PATHS = ("/docs", "/redoc", "/openapi.json")

MULTIPART_OVERHEAD = 64 * 1024  # form fields and boundaries around the image
SMALL_BODY_LIMIT = 64 * 1024  # JSON endpoints


def _header(scope: Scope, name: bytes) -> str | None:
    for key, value in scope["headers"]:
        if key == name:
            return value.decode("latin-1")
    return None


class RequestContextMiddleware:
    """Assigns a request ID (echoed as X-Request-ID) and logs one metadata-only line per request."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        incoming = _header(scope, b"x-request-id")
        request_id = incoming if incoming and _REQUEST_ID.match(incoming) else uuid.uuid4().hex
        token = request_id_var.set(request_id)
        scope.setdefault("state", {})
        started = time.perf_counter()
        status = 500

        async def send_wrapper(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                message.setdefault("headers", []).append((b"x-request-id", request_id.encode()))
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            route = scope.get("route")
            log.info(
                "request",
                extra={
                    "ctx": {
                        "method": scope["method"],
                        # Route template (e.g. /v1/session/{session_id}), never the raw path with IDs.
                        "route": getattr(route, "path", "unmatched"),
                        "status": status,
                        "duration_ms": round((time.perf_counter() - started) * 1000),
                        "device": scope["state"].get("device_hash"),
                    }
                },
            )
            request_id_var.reset(token)


class SecurityHeadersMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        is_docs = scope["path"].startswith(_DOC_PATHS)
        secure = scope.get("scheme") == "https" or _header(scope, b"x-forwarded-proto") == "https"

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = message.setdefault("headers", [])
                add = {
                    b"x-content-type-options": b"nosniff",
                    b"x-frame-options": b"DENY",
                    b"referrer-policy": b"no-referrer",
                    b"permissions-policy": b"camera=(), microphone=(), geolocation=()",
                }
                if not is_docs:
                    # Responses describe a person: never cache them. Swagger UI needs CDN scripts, so no CSP there.
                    add[b"cache-control"] = b"no-store"
                    add[b"content-security-policy"] = b"default-src 'none'; frame-ancestors 'none'"
                if secure:
                    add[b"strict-transport-security"] = b"max-age=63072000; includeSubDomains"
                present = {k.lower() for k, _ in headers}
                headers.extend((k, v) for k, v in add.items() if k not in present)
            await send(message)

        await self.app(scope, receive, send_wrapper)


class BodyLimitMiddleware:
    """Rejects oversized requests early, including chunked uploads that have no Content-Length."""

    def __init__(self, app: ASGIApp, max_image_bytes: int):
        self.app = app
        self.image_limit = max_image_bytes + MULTIPART_OVERHEAD

    def _limit(self, path: str) -> int:
        return self.image_limit if path.endswith("/describe") else SMALL_BODY_LIMIT

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        limit = self._limit(scope["path"])
        declared = _header(scope, b"content-length")
        if declared and declared.isdigit() and int(declared) > limit:
            return await self._reject(send)

        received = 0
        too_large = False
        responded = False

        async def counting_receive() -> Message:
            nonlocal received, too_large
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    too_large = True
                    # Stop feeding the parser; whatever it answers is replaced with our 413 below.
                    return {"type": "http.request", "body": b"", "more_body": False}
            return message

        async def guarded_send(message: Message) -> None:
            nonlocal responded
            if too_large:
                if not responded:
                    responded = True
                    await self._reject(send)
                return
            await send(message)

        await self.app(scope, counting_receive, guarded_send)

    @staticmethod
    async def _reject(send: Send) -> None:
        body = ErrorResponse(
            code="image_too_large",
            message="Request body is too large.",
            spoken_text="That photo is too big. Please try again.",
        )
        payload = json.dumps(body.model_dump()).encode()
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(payload)).encode())],
            }
        )
        await send({"type": "http.response.body", "body": payload})
