"""The job pipeline: provider chain -> OCR -> Groq -> card.

The ordering here is the whole point of the design. Transcription, extraction, and OCR
all happen inside a temp workspace; the LLM sees the extracted text; and only the
resulting [KnowledgeCard] is written to the database. The workspace is torn down in a
`finally`, so a job that crashes at any step still leaves nothing behind.

Sequence:

    JobWorkspace
      -> TranscriptProvider chain        (app/providers)
      -> FrameExtractor                  (optional, needs media)
      -> CardSummarizer                  (Groq)
      -> persist card
    finally: delete the workspace

Every job produces a card. A provider chain that cannot read the reel is the expected
case for Instagram, not a failure: the pipeline falls back to the caption and creator
the device supplied and labels the card partial, rather than failing and leaving the
user with nothing. Only a crash or a timeout sets the card to FAILED.
"""

from __future__ import annotations

import asyncio
import inspect
import logging
import time
from dataclasses import dataclass

from app.config import Settings
from app.models import CardStatus, JobStage, KnowledgeCard
from app.pipeline.ocr import FrameExtractor
from app.pipeline.summarizer import CardSummarizer
from app.pipeline.workspace import JobWorkspace
from app.providers.base import ProviderError, TranscriptResult
from app.providers.link_card import build_link_result
from app.providers.manual_paste import ManualPasteProvider
from app.providers.registry import run_provider_chain

logger = logging.getLogger(__name__)


@dataclass
class JobRequest:
    job_id: str
    card_id: str
    user_id: str
    reel_url: str
    transcript_override: str | None = None
    description: str | None = None
    creator: str | None = None


@dataclass
class JobOutcome:
    job_id: str
    card_id: str
    status: str
    stage: JobStage
    card: KnowledgeCard | None = None
    provider: str | None = None
    error: str | None = None
    notes: list[str] | None = None


def _notes(result: TranscriptResult, ocr_lines: list[str], size_bytes: int) -> list[str]:
    """Short diagnostics. Content-free on purpose - see the Job.notes comment."""
    notes = [f"provider:{result.provider}"]
    if result.language:
        notes.append(f"lang:{result.language}")
    if result.transcript.strip():
        notes.append(f"transcript_chars:{len(result.transcript)}")
    if result.captions:
        notes.append(f"captions:{len(result.captions)}")
    if result.hashtags:
        notes.append(f"hashtags:{len(result.hashtags)}")
    if result.creator is not None and result.creator.display_name:
        notes.append(f"creator:{result.creator.display_name}")
    if ocr_lines:
        notes.append(f"ocr_lines:{len(ocr_lines)}")
    if result.media_path is not None:
        notes.append("media:downloaded")
    notes.append(f"temp_bytes:{size_bytes}")
    if result.is_partial:
        notes.append("partial:true")
    return notes


class Pipeline:
    """Runs one job end to end. Stateless; safe to share."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.extractor = FrameExtractor(settings)
        self.summarizer = CardSummarizer(settings)

    async def run(self, request: JobRequest) -> JobOutcome:
        workspace = JobWorkspace(self.settings, request.job_id)
        try:
            async with workspace:
                return await self._run_inner(request, workspace)
        except asyncio.CancelledError:
            logger.warning("Job %s was cancelled; workspace already removed", request.job_id)
            raise
        except ProviderError as exc:
            # An expected outcome, not a bug: every provider in the chain declined.
            # The message is written for the user, so it is passed through verbatim
            # and logged without a traceback.
            logger.info("[%s] no content could be extracted: %s", request.job_id, exc)
            return JobOutcome(
                job_id=request.job_id,
                card_id=request.card_id,
                status="failed",
                stage=JobStage.FAILED,
                error=str(exc),
            )
        except Exception as exc:  # noqa: BLE001
            logger.exception("Job %s crashed", request.job_id)
            return JobOutcome(
                job_id=request.job_id,
                card_id=request.card_id,
                status="failed",
                stage=JobStage.FAILED,
                error="Something went wrong while processing this reel. Please try again.",
            )

    async def _run_inner(self, request: JobRequest, workspace: JobWorkspace) -> JobOutcome:
        # 1. Transcript.
        #    A user-pasted transcript bypasses the chain entirely: it is the highest
        #    quality source available and the app offers it precisely when a provider
        #    has failed.
        if request.transcript_override and request.transcript_override.strip():
            result = await ManualPasteProvider(request.transcript_override).fetch(
                request.reel_url, workspace.root
            )
        else:
            try:
                result = await run_provider_chain(
                    self.settings.provider_chain, request.reel_url, workspace.root
                )
            except ProviderError as exc:
                # The chain is exhausted. Not fatal: step 3 turns this into a card
                # from the caption the device supplied. Carried as a warning so the
                # app can explain why the card is thin.
                logger.info("[%s] provider chain exhausted: %s", request.job_id, exc)
                result = TranscriptResult.not_available("link", str(exc))

        for warning in result.warnings:
            logger.info("[%s] %s", request.job_id, warning)

        # 2. OCR, only if a provider actually left media behind.
        ocr_text = ""
        ocr_lines: list[str] = []
        if result.media_path is not None and self.settings.ocr_enabled:
            duration = result.metadata.get("duration_seconds")
            try:
                ocr_text, ocr_lines = await self.extractor.extract(
                    result.media_path,
                    workspace.frames_dir,
                    float(duration) if duration else None,
                )
            except Exception as exc:  # noqa: BLE001 - OCR is best effort
                logger.warning("[%s] OCR step failed, continuing: %s", request.job_id, exc)
                result.warnings.append(f"ocr: {exc}")
            result.ocr_text = ocr_text
            if ocr_lines:
                result.captions = list(dict.fromkeys([*result.captions, *ocr_lines]))

        # 3. Never dead-end the card.
        #    The provider chain being unable to read an Instagram reel is the normal
        #    case, not an error, so instead of failing the job we fall back to what
        #    the device already gave us: the caption and the creator. That keeps the
        #    card visible, reviewable and linkable, which is the whole MVP.
        if not result.has_content:
            reason = result.warnings[-1] if result.warnings else "no content was extracted"
            logger.info("[%s] falling back to a link card: %s", request.job_id, reason)
            result = build_link_result(
                request.reel_url,
                description=request.description,
                creator=request.creator,
                warnings=result.warnings,
            )
            for warning in result.warnings:
                logger.info("[%s] %s", request.job_id, warning)

        # 4. Summarise.
        card = await self.summarizer.summarize(result)

        # 5. Report before cleanup, while the workspace still exists.
        size_bytes = workspace.size_bytes()
        notes = _notes(result, ocr_lines, size_bytes)
        logger.info("[%s] %s", request.job_id, "; ".join(notes))

        return JobOutcome(
            job_id=request.job_id,
            card_id=request.card_id,
            status="succeeded",
            stage=JobStage.DONE,
            card=card,
            provider=result.provider,
            notes=notes,
        )


class PipelineRunner:
    """Bounded in-process worker pool.

    Deliberately simple. A single `uvicorn` process with a handful of threads is
    enough for an MVP and avoids a Redis/Celery dependency. To scale, reimplement
    `submit` to push onto a real queue - nothing else in the codebase changes, because
    the pipeline is a pure function of a JobRequest.
    """

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._semaphore = asyncio.Semaphore(max(1, settings.job_worker_threads))
        self._tasks: set[asyncio.Task[JobOutcome]] = set()
        self._outcomes: dict[str, JobOutcome] = {}
        # Persistence runs as its own task: the pipeline finishes on this pool but the
        # database write needs the async session. Tracked so drain()/shutdown() can
        # wait for it - a job whose result is never written looks stuck forever.
        self._persist_tasks: set[asyncio.Task] = set()

    def submit(self, request: JobRequest, on_done=None) -> asyncio.Task[JobOutcome]:
        task = asyncio.create_task(self._run(request), name=f"job:{request.job_id}")
        self._tasks.add(task)
        task.add_done_callback(lambda finished: self._finish(finished, on_done))
        return task

    def _finish(self, task: asyncio.Task[JobOutcome], on_done) -> None:
        self._tasks.discard(task)
        if task.cancelled():
            return
        exception = task.exception()
        if exception is not None:
            logger.error("Job %s failed outside the pipeline: %s", task.get_name(), exception)
            return
        outcome = task.result()
        self._outcomes[outcome.job_id] = outcome
        # Keep the map from growing without bound on a long-lived process.
        if len(self._outcomes) > 1000:
            for key in sorted(self._outcomes)[:500]:
                self._outcomes.pop(key, None)
        if on_done is None:
            return

        # `on_done` may be sync or async (Runtime.persist is async). Awaiting an
        # awaitable that nobody awaits would silently drop the database write.
        returned = on_done(outcome)
        if inspect.isawaitable(returned):
            persist_task = task.get_loop().create_task(
                returned, name=f"persist:{outcome.job_id}"
            )
            self._persist_tasks.add(persist_task)
            persist_task.add_done_callback(self._persist_tasks.discard)

    async def drain(self, timeout: float = 30.0) -> None:
        """Block until every in-flight job and its database write has finished.

        Used by tests, and by any caller that needs a job's card to be readable the
        moment submit() returns.
        """
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while (self._tasks or self._persist_tasks) and loop.time() < deadline:
            pending = [*self._tasks, *self._persist_tasks]
            await asyncio.wait(pending, timeout=0.25)

    async def _run(self, request: JobRequest) -> JobOutcome:
        async with self._semaphore:
            started = time.monotonic()
            pipeline = Pipeline(self.settings)
            try:
                outcome = await asyncio.wait_for(
                    pipeline.run(request), timeout=self.settings.job_timeout_seconds
                )
            except asyncio.TimeoutError:
                logger.error("Job %s timed out after %ds", request.job_id, self.settings.job_timeout_seconds)
                outcome = JobOutcome(
                    job_id=request.job_id,
                    card_id=request.card_id,
                    status="failed",
                    stage=JobStage.FAILED,
                    error=f"timed out after {self.settings.job_timeout_seconds:.0f}s",
                )
            logger.info(
                "[%s] finished %s in %.1fs",
                request.job_id,
                outcome.status,
                time.monotonic() - started,
            )
            return outcome

    async def shutdown(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        for task in list(self._persist_tasks):
            task.cancel()
        pending = [*self._tasks, *self._persist_tasks]
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        self._tasks.clear()
        self._persist_tasks.clear()
