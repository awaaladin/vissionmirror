from fastapi import Request

from app.services.describe_service import DescribeService
from app.services.session_store import SessionStore


def get_service(request: Request) -> DescribeService:
    return request.app.state.service


def get_store(request: Request) -> SessionStore:
    return request.app.state.store
