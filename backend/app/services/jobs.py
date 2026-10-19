"""Persisting the result of a finished pipeline job.

The only place that writes generated card content. Everything here takes content that
has already been summarised; no transcript, caption, or OCR text ever reaches a
column.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Card, Job
from app.models import CardStatus
from app.pipeline.runner import JobOutcome

logger = logging.getLogger(__name__)


async def complete(session: AsyncSession, outcome: JobOutcome) -> Card | None:
    """Write a finished job's outcome onto its card. Caller commits."""
    job = await session.get(Job, outcome.job_id)
    if job is None:
        logger.error("Job %s vanished before it could be persisted", outcome.job_id)
        return None

    card = await session.get(Card, outcome.card_id)
    if card is None:
        logger.error("Card %s vanished before its job could be persisted", outcome.card_id)
        return None

    now = datetime.now(timezone.utc)
    job.stage = outcome.stage
    job.error = outcome.error
    job.provider = outcome.provider
    job.notes = "; ".join(outcome.notes or []) if outcome.notes else None
    job.attempt += 1
    job.finished_at = now

    if outcome.status == "succeeded" and outcome.card is not None:
        generated = outcome.card
        card.title = generated.title
        card.summary = generated.summary
        card.description = generated.description
        card.hashtags = json.dumps(generated.hashtags)
        card.creator_name = generated.creator_name
        card.creator_username = generated.creator_username
        card.creator_bio = generated.creator_bio
        card.thumbnail_url = generated.thumbnail_url
        card.key_insights = json.dumps(generated.key_insights)
        card.action_items = json.dumps(generated.action_items)
        card.topics = json.dumps(generated.topics)
        card.status = CardStatus.PENDING
        card.provider = outcome.provider
        card.last_error = None
    else:
        card.status = CardStatus.FAILED
        card.last_error = outcome.error or "The reel could not be processed."

    card.updated_at = now
    return card


async def complete_async(session: AsyncSession, outcome: JobOutcome) -> Card | None:
    try:
        card = await complete(session, outcome)
        await session.commit()
        if card is not None:
            logger.info("Persisted card %s as %s", card.id, card.status.value)
        return card
    except Exception:  # noqa: BLE001
        await session.rollback()
        logger.exception("Failed to persist job %s", outcome.job_id)
        return None
