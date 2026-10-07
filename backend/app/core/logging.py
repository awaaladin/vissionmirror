"""Structured JSON logs. Log lines carry request IDs and metadata only, never payload content:
no images, descriptions, questions, answers, tokens or API keys."""

import json
import logging
from contextvars import ContextVar

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
            "request_id": request_id_var.get(),
        }
        # Callers pass small metadata dicts via extra={"ctx": {...}}.
        entry.update(getattr(record, "ctx", {}))
        if record.exc_info:
            entry["exc_type"] = record.exc_info[0].__name__  # type only: messages can echo payloads
        return json.dumps(entry, default=str)


def setup_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
    # Uvicorn's access log prints raw paths (which include session IDs); our request log replaces it.
    logging.getLogger("uvicorn.access").disabled = True
    # httpx logs full request URLs at INFO.
    logging.getLogger("httpx").setLevel(logging.WARNING)
