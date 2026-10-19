"""Shared fixtures.

Every test runs against an isolated SQLite file plus an isolated workspace
directory, so nothing a test writes can reach a real user, a real reel, or the
developer's own storage directory.
"""

from __future__ import annotations

import asyncio
import os
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

os.environ["ENV"] = "test"
# Keep tests offline and deterministic: no LLM call, no frame sampling.
os.environ["GROQ_API_KEY"] = ""
os.environ["OCR_ENABLED"] = "false"


@pytest.fixture
def settings(tmp_path: Path):
    from app.config import Settings

    config = Settings(
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'test.sqlite3'}",
        workspace_dir=tmp_path / "workspace",
        local_user_id="test-user",
        groq_api_key="",
        transcript_provider="manual",
        ocr_enabled=False,
        env="test",
    )
    config.ensure_directories()
    return config


@pytest_asyncio.fixture
async def runtime(settings):
    """A fully wired Runtime with a real (temporary) database."""
    from app.db import Base
    from app.pipeline.runner import PipelineRunner
    from app.api import Runtime

    engine = create_async_engine(settings.database_url, future=True)
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)

    factory = async_sessionmaker(engine, expire_on_commit=False)
    runner = PipelineRunner(settings)

    container = Runtime(settings=settings, session_factory=factory, runner=runner)
    try:
        yield container
    finally:
        await runner.shutdown()
        await engine.dispose()


@pytest_asyncio.fixture
async def client(runtime) -> AsyncIterator[AsyncClient]:
    from app.config import get_settings
    from app.main import create_app

    app = create_app()
    app.state.runtime = runtime
    # The app resolves `get_settings` from its process-wide lru_cache, which is built
    # from the real .env. The tests feed a Runtime with its own Settings, so without
    # this override the API would use `local-user` while the fixtures use `test-user`.
    app.dependency_overrides[get_settings] = lambda: runtime.settings

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as http:
        yield http


@pytest.fixture
def reel_url() -> str:
    return "https://www.instagram.com/reel/C1234567890abcdef/"
