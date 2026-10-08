"""Vercel entrypoint: Vercel's Python runtime serves the ASGI object named `app` from this file."""
from app.main import create_app

app = create_app()
