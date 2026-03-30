from __future__ import annotations

from app.api.main import create_fastapi_app


def create_app():
    return create_fastapi_app()


app = create_app()
