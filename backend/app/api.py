"""FastAPI routes and the runtime container.

Endpoints mirror what the Android client expects in
app/src/main/java/com/resourcetracker/data/remote/BackendApi.kt:

    POST   /v1/reels           submit a shared URL -> job + card ids
    GET    /v1/jobs/{job_id}   poll for progress and the finished card
    GET    /v1/cards           list, optionally filtered by status
    GET    /v1/cards/{id}      one card
    PATCH  /v1/cards/{id}      review / snooze
    DELETE /v1/cards/{id}      soft delete
    GET    /v1/health          config and provider availability

Ownership is enforced in app/services/cards.py on every read and write using the
single configured local user (app/identity.py). Submitting is idempotent on
`client_card_id`, so a phone retry after a dropped response does not create a
duplicate.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.config import Settings, get_settings
from app.db import Card, Job
from app.identity import current_user_id
from app.ratelimit import RateLimiter
from app.models import (
    CardResponse,
    CardStatus,
    HealthResponse,
    JobAcceptedResponse,
    JobStatusResponse,
    SubmitReelRequest,
    UpdateCardRequest,
)
from app.pipeline.runner import JobOutcome, JobRequest, PipelineRunner
from app.pipeline.workspace import new_job_id
from app.providers.registry import describe_providers
from app.services import cards as card_service
from app.services import jobs as job_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1")


@dataclass
class Runtime:
    """Long-lived objects created once in the lifespan and shared by all requests."""

    settings: Settings
    session_factory: async_sessionmaker[AsyncSession]
    runner: PipelineRunner
    submit_limiter: RateLimiter = field(init=False)

    def __post_init__(self) -> None:
        self.submit_limiter = RateLimiter(self.settings.submit_rate_limit_per_minute)

    async def persist(self, outcome: JobOutcome) -> None:
        """Called on the pipeline thread once a job finishes."""
        async with self.session_factory() as session:
            await job_service.complete_async(session, outcome)


def get_runtime(request: Request) -> Runtime:
    runtime: Runtime | None = getattr(request.app.state, "runtime", None)
    if runtime is None:  # pragma: no cover - only if lifespan did not run
        raise HTTPException(status_code=503, detail="Server is still starting")
    return runtime


async def get_db(runtime: Runtime = Depends(get_runtime)) -> AsyncSession:
    session = runtime.session_factory()
    try:
        yield session
    finally:
        await session.close()


@router.get("/health", response_model=HealthResponse, tags=["meta"])
async def health(settings: Settings = Depends(get_settings)) -> HealthResponse:
    """Unauthenticated. Reports booleans about configuration, never any secret value."""
    return HealthResponse(
        status="ok",
        version=settings.version,
        active_provider=settings.provider_chain[0] if settings.provider_chain else "none",
        providers=[
            {
                "name": str(entry["name"]),
                "available": bool(entry["available"]),
                "description": str(entry.get("description") or ""),
            }
            for entry in describe_providers()
        ],
        llm_configured=settings.llm_configured,
    )


@router.post(
    "/reels",
    response_model=JobAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["reels"],
)
async def submit_reel(
    payload: SubmitReelRequest,
    request: Request,
    response: Response,
    user_id: str = Depends(current_user_id),
    runtime: Runtime = Depends(get_runtime),
    session: AsyncSession = Depends(get_db),
) -> JobAcceptedResponse:
    """Accept a reel URL and start processing."""
    client_key = request.client.host if request.client else "unknown"
    if not runtime.submit_limiter.check(client_key):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many submissions; wait a moment and try again.",
        )

    existing = await card_service.owned_card(session, payload.client_card_id, user_id)
    if existing is not None:
        job = await card_service.latest_job(session, existing.id)
        if job is not None:
            response.status_code = status.HTTP_200_OK
            return JobAcceptedResponse(
                job_id=job.id,
                card_id=existing.id,
                status=card_service.job_status(job),
                provider=job.provider,
            )
        # The card exists but has no job (a previous submit committed the card and
        # then crashed before the job row). Reuse it instead of inserting a second
        # row with the same primary key, which is an IntegrityError -> HTTP 500.
        card = existing
        card.reel_url = payload.reel_url
        card.title = "Preparing your card"
        card.summary = "Fetching the reel and building a summary. This usually takes under a minute."
        card.status = CardStatus.QUEUED
        card.last_error = None
    elif await card_service.get_card(session, payload.client_card_id) is not None:
        # The id is taken by a row this user does not own. Do not crash on the insert.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="That card id is already in use",
        )
    else:
        card = Card(
            id=payload.client_card_id,
            user_id=user_id,
            reel_url=payload.reel_url,
            title="Preparing your card",
            summary="Fetching the reel and building a summary. This usually takes under a minute.",
            status=CardStatus.QUEUED,
        )
        session.add(card)

    job = Job(id=new_job_id(), card_id=card.id, user_id=user_id, stage="QUEUED")
    session.add(job)
    await session.commit()

    runtime.runner.submit(
        JobRequest(
            job_id=job.id,
            card_id=card.id,
            user_id=user_id,
            reel_url=payload.reel_url,
            transcript_override=payload.transcript_override,
            description=payload.description,
            creator=payload.creator,
        ),
        on_done=runtime.persist,
    )

    return JobAcceptedResponse(
        job_id=job.id,
        card_id=card.id,
        status="queued",
        provider=(
            runtime.settings.provider_chain[0] if runtime.settings.provider_chain else None
        ),
    )


@router.get("/jobs/{job_id}", response_model=JobStatusResponse, tags=["reels"])
async def get_job(
    job_id: str,
    user_id: str = Depends(current_user_id),
    session: AsyncSession = Depends(get_db),
) -> JobStatusResponse:
    job, card = await card_service.get_job_with_card(session, job_id, user_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return JobStatusResponse(
        job_id=job.id,
        card_id=job.card_id,
        status=card_service.job_status(job),
        stage=job.stage,
        error=job.error,
        provider=job.provider,
        card=card_service.to_response(card) if card is not None else None,
    )


@router.get("/cards", response_model=list[CardResponse], tags=["cards"])
async def list_cards(
    status_filter: CardStatus | None = Query(default=None, alias="status"),
    limit: int = Query(default=100, ge=1, le=500),
    user_id: str = Depends(current_user_id),
    session: AsyncSession = Depends(get_db),
) -> list[CardResponse]:
    """List the caller's cards, newest first. DELETED cards are excluded."""
    found = await card_service.list_cards(
        session, user_id, status_filter=status_filter, limit=limit
    )
    return [card_service.to_response(card) for card in found]


@router.get("/cards/{card_id}", response_model=CardResponse, tags=["cards"])
async def get_card(
    card_id: str,
    user_id: str = Depends(current_user_id),
    session: AsyncSession = Depends(get_db),
) -> CardResponse:
    card = await card_service.owned_card(session, card_id, user_id)
    if card is None:
        raise HTTPException(status_code=404, detail="Card not found")
    return card_service.to_response(card)


@router.patch("/cards/{card_id}", response_model=CardResponse, tags=["cards"])
async def update_card(
    card_id: str,
    payload: UpdateCardRequest,
    user_id: str = Depends(current_user_id),
    session: AsyncSession = Depends(get_db),
) -> CardResponse:
    card = await card_service.owned_card(session, card_id, user_id)
    if card is None:
        raise HTTPException(status_code=404, detail="Card not found")
    return card_service.to_response(await card_service.apply_update(session, card, payload))


@router.delete(
    "/cards/{card_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
    tags=["cards"],
)
async def delete_card(
    card_id: str,
    user_id: str = Depends(current_user_id),
    session: AsyncSession = Depends(get_db),
) -> Response:
    """Soft delete: the row is kept with status DELETED so the phone's local delete
    survives a later sync instead of the card reappearing.

    Returns an explicit empty Response because FastAPI would otherwise try to build a
    body for a 204.
    """
    card = await card_service.owned_card(session, card_id, user_id)
    if card is None:
        raise HTTPException(status_code=404, detail="Card not found")
    await card_service.apply_update(
        session, card, UpdateCardRequest(status=CardStatus.DELETED)
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
