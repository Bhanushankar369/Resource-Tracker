"""Application entrypoint.

Wires the lifespan: settings, database, the in-process job runner, and the workspace
janitor. The janitor is started here rather than lazily because a leaked workspace is
the one failure mode that only shows up much later, and the process being alive is
exactly when cleanup should be happening.
"""

from __future__ import annotations

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.api import Runtime, router
from app.config import get_settings
from app.db import Base, ensure_card_columns
from app.pipeline.runner import PipelineRunner
from app.pipeline.workspace import Janitor

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
)
logger = logging.getLogger("resourcetracker")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    settings.ensure_directories()

    if not settings.llm_configured:
        logger.warning(
            "GROQ_API_KEY is not set. Cards will use the deterministic extractive "
            "fallback instead of a real LLM summary."
        )

    # SQLite needs this to enforce the foreign keys the Job -> Card relation relies on.
    if settings.database_url.startswith("sqlite"):
        from sqlalchemy import event
        from sqlalchemy.engine import Engine

        engine = create_async_engine(settings.database_url, future=True)

        @event.listens_for(Engine, "connect")
        def _sqlite_pragmas(dbapi_connection, _record):  # pragma: no cover - driver hook
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.close()

    else:
        engine = create_async_engine(settings.database_url, future=True, pool_pre_ping=True)

    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
        # Column-level additions for databases created before those columns existed.
        await connection.run_sync(ensure_card_columns)

    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    runner = PipelineRunner(settings)
    janitor = Janitor(settings)

    app.state.runtime = Runtime(
        settings=settings, session_factory=session_factory, runner=runner
    )
    app.state.engine = engine

    janitor_task = asyncio.create_task(janitor.run_forever(), name="janitor")

    logger.info(
        "%s v%s ready: provider=%s llm=%s user=%s",
        settings.app_name,
        settings.version,
        settings.provider_chain,
        settings.llm_configured,
        settings.local_user_id,
    )

    try:
        yield
    finally:
        logger.info("Shutting down")
        await runner.shutdown()
        janitor_task.cancel()
        try:
            await janitor_task
        except asyncio.CancelledError:
            pass
        # Last line of defence: drop anything still on disk before we exit.
        for name in Janitor(settings).sweep():
            logger.warning("Cleaned up workspace %s on shutdown", name)
        await engine.dispose()


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title=settings.app_name,
        version=settings.version,
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
    )

    origins = settings.cors_origin_list
    if origins:
        # Credentials off: there is no cookie auth, so there is no CSRF surface, and
        # only the headers a browser actually needs are allowed through.
        app.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
            allow_headers=["Content-Type"],
        )

    app.include_router(router)
    return app


app = create_app()


if __name__ == "__main__":  # pragma: no cover
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=os.getenv("HOST", "0.0.0.0"),
        port=int(os.getenv("PORT", "8000")),
        reload=os.getenv("RELOAD", "").lower() in ("1", "true", "yes"),
    )
