"""Frame sampling and OCR.

Runs inside a job workspace and writes nothing outside it. Frames are deleted by the
workspace teardown; this module does not keep a copy anywhere.

Two levels of degradation, because a missing tesseract binary is a normal thing to
hit on a dev box and must not fail a job:

  * ffmpeg missing -> no frames sampled, an OCR-free card is still produced
  * ffmpeg present, tesseract missing -> frames sampled, OCR skipped with a warning

Requires `pip install pytesseract opencv-python-headless` and the tesseract binary on
PATH. `is_ocr_available()` reports what is actually usable so the health endpoint can
say so before a user wonders why cards are thin.
"""

from __future__ import annotations

import asyncio
import logging
import shutil
import subprocess
from pathlib import Path
from typing import ClassVar

from app.config import Settings

logger = logging.getLogger(__name__)

# OCR over many near-identical frames returns the same words repeatedly. Collapse
# them to distinct lines, preserving order.
_NOISE = {"", ".", ",", "!", "?", "'", '"', "..."}


def is_ocr_available(settings: Settings) -> bool:
    if not settings.ocr_enabled:
        return False
    if shutil.which(settings.ffmpeg_binary) is None:
        return False
    try:
        import cv2  # noqa: F401
        import pytesseract  # noqa: F401
    except ImportError:
        return False
    return True


def _run(cmd: list[str], timeout: float) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
        onerror=lambda *_: None,
    )


def sample_frames(
    settings: Settings,
    media_path: Path,
    frames_dir: Path,
    duration_seconds: float | None = None,
) -> list[Path]:
    """Sample frames evenly across the clip with ffmpeg.

    Returns the frames that were written. Raises nothing: a failed sample is reported
    as an empty list so the caller can carry on without OCR.
    """
    ffmpeg = shutil.which(settings.ffmpeg_binary)
    if ffmpeg is None:
        logger.info("ffmpeg not found, skipping frame sampling")
        return []

    count = max(1, settings.ocr_frame_count)
    if duration_seconds and duration_seconds > 0:
        # Start a little way in: the first frames are usually a title card or fade-in.
        start = min(1.0, duration_seconds * 0.05)
        span = max(0.1, duration_seconds - start - 0.2)
        step = span / count
    else:
        start, step = 0.5, 2.0

    written: list[Path] = []
    for index in range(count):
        timestamp = start + step * index
        destination = frames_dir / f"frame_{index:03d}.jpg"
        result = _run(
            [
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", f"{timestamp:.2f}", "-i", str(media_path),
                "-frames:v", "1", "-q:v", "3", str(destination),
            ],
            timeout=settings.download_timeout_seconds,
        )
        if result.returncode != 0 or not destination.exists():
            logger.debug("Frame sample at %.2fs failed: %s", timestamp, result.stderr.strip()[:200])
            continue
        written.append(destination)

    logger.info("Sampled %d/%d frames", len(written), count)
    return written


def _read_duration(settings: Settings, media_path: Path) -> float | None:
    """Container duration via ffprobe when available, else None."""
    ffprobe = shutil.which("ffprobe")
    if ffprobe is None:
        return None
    result = _run(
        [
            ffprobe, "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(media_path),
        ],
        timeout=30,
    )
    try:
        return float(result.stdout.strip())
    except (TypeError, ValueError):
        return None


def ocr_frames(frames: list[Path]) -> tuple[str, list[str]]:
    """Run tesseract over sampled frames.

    Returns (combined_text, distinct_lines). Distinct lines are what get handed to
    the LLM as candidate captions; the combined text is kept for debugging inside
    the workspace only.
    """
    if not frames:
        return "", []

    try:
        import cv2
        import pytesseract
    except ImportError as exc:
        logger.warning("OCR libraries unavailable: %s", exc)
        return "", []

    distinct: list[str] = []
    seen: set[str] = set()
    blocks: list[str] = []

    for frame in frames:
        try:
            image = cv2.imread(str(frame))
            if image is None:
                continue
            text = pytesseract.image_to_string(image, lang="eng")
        except Exception as exc:  # noqa: BLE001 - OCR must not kill the job
            logger.warning("OCR failed on %s: %s", frame.name, exc)
            continue

        cleaned = text.strip()
        if cleaned:
            blocks.append(f"[{frame.stem}]\n{cleaned}")
        for line in cleaned.splitlines():
            normalised = " ".join(line.split()).strip(" |:-")
            if normalised in _NOISE:
                continue
            key = normalised.lower()
            if key in seen:
                continue
            seen.add(key)
            distinct.append(normalised)

    combined = "\n\n".join(blocks)
    logger.info("OCR produced %d distinct lines", len(distinct))
    return combined, distinct


class FrameExtractor:
    """Async facade so the pipeline does not block its event loop on ffmpeg/tesseract."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    async def extract(
        self,
        media_path: Path | None,
        frames_dir: Path,
        duration_seconds: float | None = None,
    ) -> tuple[str, list[str]]:
        if media_path is None or not media_path.exists():
            return "", []
        if not self.settings.ocr_enabled or not is_ocr_available(self.settings):
            logger.info("OCR disabled or unavailable, skipping")
            return "", []
        return await asyncio.to_thread(self._extract_sync, media_path, frames_dir, duration_seconds)

    def _extract_sync(
        self,
        media_path: Path,
        frames_dir: Path,
        duration_seconds: float | None,
    ) -> tuple[str, list[str]]:
        frames = sample_frames(
            self.settings,
            media_path,
            frames_dir,
            duration_seconds or _read_duration(self.settings, media_path),
        )
        return ocr_frames(frames)
