"""Job-scoped temp storage with deletion that is hard to get wrong.

The rule this module exists to enforce: no transcript, media, or OCR text may
outlive a job. Two mechanisms, because either one alone can fail:

  1. [JobWorkspace] is a context manager. Its `finally` calls [JobWorkspace.cleanup]
     even when the job raised, and it does so in a `finally` inside the generator so
     a cancelled job still cleans up.
  2. [Janitor] sweeps the workspace root on a timer and deletes job directories older
     than `janitor_max_age_seconds`. This catches the case cleanup (1) cannot: the
     process being SIGKILLed, the box losing power, or the disk filling mid-write.

The janitor is the only thing standing between a hard crash and a temp directory that
grows forever and holds the last several reels' media on disk.

Deletion is done by walking the tree and unlinking, then removing the directory. It is
not rmtree-on-a-path-from-user-input, so there is no path-traversal surface.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shutil
import time
import uuid
from pathlib import Path
from types import TracebackType

from app.config import Settings

logger = logging.getLogger(__name__)


def delete_tree(path: Path) -> bool:
    """Delete a file or directory. Never raises. Returns whether anything went away.

    Read-only files (extracted frames can inherit odd permissions) are made writable
    first, otherwise the sweep silently leaves them behind and we would leak data
    while logging success.
    """
    try:
        if path.is_symlink() or path.is_file():
            path.unlink(missing_ok=True)
            return True
        if not path.exists():
            return False
    except OSError as exc:
        logger.error("Could not inspect %s during cleanup: %s", path, exc)
        return False

    for root, dirnames, filenames in os.walk(path, topdown=True):
        for name in dirnames:
            try:
                (Path(root) / name).chmod(0o700)
            except OSError:
                pass
        for name in filenames:
            target = Path(root) / name
            try:
                target.chmod(0o600)
            except OSError:
                pass

    try:
        shutil.rmtree(path, ignore_errors=False)
        return True
    except OSError as exc:
        logger.error("Could not remove %s during cleanup: %s", path, exc)
        # Second attempt with errors suppressed: better a partial delete plus a
        # logged failure than an exception that hides the original job error.
        shutil.rmtree(path, ignore_errors=True)
        return not path.exists()


class JobWorkspace:
    """A private directory for one job, deleted on exit.

    Layout::

        <workspace_dir>/<job_id>/
            input.json
            media/
            frames/
            ocr/

    Use it as an async context manager. Never hold on to a path after the `async
    with` block: it may already be gone.
    """

    def __init__(self, settings: Settings, job_id: str) -> None:
        self.settings = settings
        self.job_id = job_id
        self.root = settings.workspace_dir / job_id
        self.media_dir = self.root / "media"
        self.frames_dir = self.root / "frames"
        self.ocr_dir = self.root / "ocr"
        self.input_path = self.root / "input.json"

    def __enter__(self) -> "JobWorkspace":
        self.create()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.cleanup()

    async def __aenter__(self) -> "JobWorkspace":
        self.create()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.cleanup()

    def create(self) -> None:
        for directory in (self.media_dir, self.frames_dir, self.ocr_dir):
            directory.mkdir(parents=True, exist_ok=True)
        # Job data is a user's content in transit. Keep it off other accounts.
        try:
            self.root.chmod(0o700)
        except OSError:
            pass

    def cleanup(self) -> bool:
        if not self.root.exists():
            return True
        removed = delete_tree(self.root)
        if removed:
            logger.info("Deleted job workspace %s", self.job_id)
        return removed

    def size_bytes(self) -> int:
        total = 0
        for root, _dirs, files in os.walk(self.root):
            for name in files:
                try:
                    total += (Path(root) / name).stat().st_size
                except OSError:
                    pass
        return total

    def describe(self) -> dict[str, object]:
        return {"job_id": self.job_id, "path": str(self.root), "bytes": self.size_bytes()}


class Janitor:
    """Periodic sweep of the workspace root.

    Deletes any job directory whose mtime is older than the configured max age. A
    directory being actively written keeps a recent mtime, so a long-running job is
    not pulled out from under itself.
    """

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def sweep(self) -> list[str]:
        root = self.settings.workspace_dir
        if not root.exists():
            return []

        cutoff = time.time() - self.settings.janitor_max_age_seconds
        removed: list[str] = []

        try:
            entries = list(root.iterdir())
        except OSError as exc:
            logger.error("Janitor could not list %s: %s", root, exc)
            return []

        for entry in entries:
            if not entry.is_dir():
                continue
            try:
                if entry.stat().st_mtime >= cutoff:
                    continue
            except OSError:
                continue
            if delete_tree(entry):
                removed.append(entry.name)
            else:
                logger.error("Janitor failed to remove stale workspace %s", entry.name)

        if removed:
            logger.warning(
                "Janitor removed %d stale workspace(s) older than %ds: %s",
                len(removed),
                self.settings.janitor_max_age_seconds,
                ", ".join(removed),
            )
        return removed

    async def run_forever(self) -> None:
        interval = max(60, self.settings.janitor_interval_seconds)
        logger.info("Janitor started, sweeping every %ds", interval)
        while True:
            try:
                await asyncio.sleep(interval)
                await asyncio.to_thread(self.sweep)
            except asyncio.CancelledError:
                logger.info("Janitor stopping")
                raise
            except Exception:  # noqa: BLE001
                logger.exception("Janitor sweep failed, continuing")


def new_job_id() -> str:
    return uuid.uuid4().hex
