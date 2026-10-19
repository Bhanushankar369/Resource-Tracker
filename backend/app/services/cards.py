"""Card persistence and ownership rules.

Every read and write goes through this module so the "is this caller the owner?"
check cannot be forgotten by a route. A card that belongs to another user is
indistinguishable from one that does not exist, which avoids leaking that an id is
real.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Card, Job
from app.models import CardResponse, CardStatus, UpdateCardRequest

logger = logging.getLogger(__name__)


def _loads(raw: str | None) -> list[str]:
    if not raw:
        return []
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("Discarding unparseable list column: %r", raw[:80])
        return []
    return [str(item) for item in value] if isinstance(value, list) else []


def to_response(card: Card) -> CardResponse:
    return CardResponse(
        id=card.id,
        reel_url=card.reel_url,
        title=card.title,
        summary=card.summary,
        description=card.description,
        hashtags=_loads(card.hashtags),
        creator_name=card.creator_name,
        creator_username=card.creator_username,
        creator_bio=card.creator_bio,
        key_insights=_loads(card.key_insights),
        action_items=_loads(card.action_items),
        topics=_loads(card.topics),
        status=card.status,
        created_at=card.created_at,
        reviewed_at=card.reviewed_at,
        snooze_until_epoch_day=card.snooze_until_epoch_day,
        provider=card.provider,
        thumbnail_url=card.thumbnail_url,
        last_error=card.last_error,
    )


async def get_card(session: AsyncSession, card_id: str) -> Card | None:
    return await session.get(Card, card_id)


async def owned_card(session: AsyncSession, card_id: str, user_id: str) -> Card | None:
    """Fetch a card only if `user_id` owns it. None means 'not yours or not real'."""
    card = await session.get(Card, card_id)
    if card is None or card.user_id != user_id:
        return None
    return card


async def list_cards(
    session: AsyncSession,
    user_id: str,
    status_filter: CardStatus | None = None,
    limit: int = 100,
) -> list[Card]:
    stmt = select(Card).where(Card.user_id == user_id, Card.status != CardStatus.DELETED)
    if status_filter is not None:
        stmt = stmt.where(Card.status == status_filter)
    stmt = stmt.order_by(Card.created_at.desc()).limit(limit)
    result = await session.execute(stmt)
    return list(result.scalars().all())


async def latest_job(session: AsyncSession, card_id: str) -> Job | None:
    stmt = (
        select(Job)
        .where(Job.card_id == card_id)
        .order_by(Job.created_at.desc())
        .limit(1)
    )
    result = await session.execute(stmt)
    return result.scalar_one_or_none()


def job_status(job: Job) -> str:
    """Normalise a job's lifecycle state for the API.

    The `Job.stage` enum records which step was reached, which is not the same thing
    as whether the job succeeded - a job that reached DONE and then failed
    persistence reports FAILED here. Derived rather than stored so the two can never
    drift apart.
    """
    if job.finished_at is None:
        return "queued"
    return "failed" if job.error else "succeeded"


async def get_job_with_card(
    session: AsyncSession, job_id: str, user_id: str
) -> tuple[Job | None, Card | None]:
    result = await session.execute(select(Job).where(Job.id == job_id))
    job = result.scalar_one_or_none()
    if job is None or job.user_id != user_id:
        return None, None
    return job, await session.get(Card, job.card_id)


async def apply_update(session: AsyncSession, card: Card, payload: UpdateCardRequest) -> Card:
    """Apply a client action.

    Only REVIEWED / SNOOZED / DELETED are accepted from the client. A client must not
    be able to talk the server into marking a card PENDING or wiping its summary, so
    those transitions are ignored rather than rejected - an older app build sending an
    unknown status should not hard-fail.
    """
    if payload.status in (CardStatus.REVIEWED, CardStatus.SNOOZED, CardStatus.DELETED):
        card.status = payload.status
        card.reviewed_at = datetime.now(timezone.utc) if payload.status is CardStatus.REVIEWED else None
        card.snooze_until_epoch_day = (
            payload.snooze_until_epoch_day if payload.status is CardStatus.SNOOZED else None
        )
        if payload.status is CardStatus.DELETED:
            card.last_error = None
    elif payload.status is not None:
        logger.warning("Ignoring client-requested card status %s", payload.status.value)

    if payload.snooze_until_epoch_day is not None and payload.status is None:
        card.snooze_until_epoch_day = payload.snooze_until_epoch_day
        card.status = CardStatus.SNOOZED

    card.updated_at = datetime.now(timezone.utc)
    await session.commit()
    await session.refresh(card)
    return card
