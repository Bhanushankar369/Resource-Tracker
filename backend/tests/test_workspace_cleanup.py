"""Tests for the temp-artifact policy.

This is the requirement most worth testing, because a regression here is silent and
invisible: nothing crashes, the app keeps working, and the only symptom is that reels
accumulate on a server disk forever. So these tests assert on the filesystem, not on
return values.
"""

from __future__ import annotations

import asyncio
import os
import time
from pathlib import Path

import pytest

from app.pipeline.runner import JobRequest, Pipeline
from app.pipeline.workspace import Janitor, JobWorkspace, delete_tree
from app.providers.base import ProviderError, TranscriptResult


class _NoisyProvider:
    """Stands in for a real provider: writes media, frames, and OCR junk."""

    name = "noisy"
    description = "test provider that litters"

    def is_available(self) -> bool:
        return True

    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        (workspace / "media").mkdir(parents=True, exist_ok=True)
        (workspace / "media" / "video.mp4").write_bytes(b"x" * 2048)
        (workspace / "frames").mkdir(parents=True, exist_ok=True)
        (workspace / "frames" / "frame_000.jpg").write_bytes(b"y" * 512)
        (workspace / "ocr").mkdir(parents=True, exist_ok=True)
        (workspace / "ocr" / "dump.txt").write_text("sensitive transcript text")
        return TranscriptResult(
            provider=self.name,
            transcript="The secret sauce is two parts soy to one part vinegar.",
            is_partial=True,
        )


async def test_workspace_is_deleted_after_a_successful_job(settings, monkeypatch):
    # runner.py does `from ... import run_provider_chain`, so the name to patch is the
    # one in the runner module, not in registry.
    monkeypatch.setattr("app.pipeline.runner.run_provider_chain", _fake_chain(_NoisyProvider()))

    pipeline = Pipeline(settings)
    outcome = await pipeline.run(
        JobRequest(job_id="job-ok", card_id="card-ok", user_id="u1", reel_url="https://x/1")
    )

    assert outcome.status == "succeeded"
    assert outcome.card is not None
    assert not (settings.workspace_dir / "job-ok").exists(), "temp files survived a good job"
    assert not any(settings.workspace_dir.iterdir()), "workspace root should be empty"


async def test_workspace_is_deleted_after_a_crashing_job(settings, monkeypatch):
    class _Boom:
        name = "boom"
        description = "raises after writing files"

        def is_available(self) -> bool:
            return True

        async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
            (workspace / "media").mkdir(parents=True, exist_ok=True)
            (workspace / "media" / "half-written.mp4").write_bytes(b"partial download")
            raise ProviderError("download died halfway")

    monkeypatch.setattr("app.pipeline.runner.run_provider_chain", _fake_chain(_Boom()))

    pipeline = Pipeline(settings)
    outcome = await pipeline.run(
        JobRequest(job_id="job-bad", card_id="card-bad", user_id="u1", reel_url="https://x/2")
    )

    # The provider blew up, but the caption the device supplied is still enough to
    # build a card, so the job succeeds. What matters here is only that the partial
    # download never reaches disk.
    assert outcome.status == "succeeded"
    assert outcome.card is not None
    assert not (settings.workspace_dir / "job-bad").exists(), (
        "a job that fell back leaked its partial download to disk"
    )


async def test_summariser_crash_still_cleans_up(settings, monkeypatch):
    class _Exploding:
        async def summarize(self, _result):
            raise RuntimeError("summariser blew up")

    pipeline = Pipeline(settings)
    monkeypatch.setattr(pipeline, "summarizer", _Exploding())
    monkeypatch.setattr("app.providers.registry.run_provider_chain", _fake_chain(_NoisyProvider()))

    outcome = await pipeline.run(
        JobRequest(job_id="job-s", card_id="card-s", user_id="u1", reel_url="https://x/3")
    )

    assert outcome.status == "failed"
    assert not (settings.workspace_dir / "job-s").exists()


def test_janitor_removes_a_stale_workspace(settings):
    stale = settings.workspace_dir / "stale-job"
    (stale / "media").mkdir(parents=True)
    (stale / "media" / "video.mp4").write_bytes(b"z" * 100)

    # Fresh mtime means a running job, which must be left alone.
    fresh = settings.workspace_dir / "running-job"
    (fresh / "media").mkdir(parents=True)
    (fresh / "media" / "video.mp4").write_bytes(b"z" * 100)

    # Simulate a process that was SIGKILLed mid-job: the directory is old.
    old = time.time() - (settings.janitor_max_age_seconds * 2)
    os.utime(stale, (old, old))

    removed = Janitor(settings).sweep()
    assert removed == ["stale-job"]
    assert not stale.exists()
    assert fresh.exists(), "janitor deleted a job that was still running"


def test_janitor_is_a_noop_on_an_empty_root(settings):
    assert Janitor(settings).sweep() == []


def test_delete_tree_survives_readonly_files(settings):
    target = settings.workspace_dir / "ro"
    target.mkdir(parents=True, exist_ok=True)
    readonly = target / "locked.bin"
    readonly.write_bytes(b"data")
    readonly.chmod(0o400)

    assert delete_tree(target) is True
    assert not target.exists()


def _fake_chain(provider):
    async def _run(_chain, _reel_url, workspace):
        return await provider.fetch("unused", workspace)

    return _run
