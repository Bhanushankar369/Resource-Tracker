"""Provider that transcribes reel audio with Whisper.

Requires `media_url`, which no free Instagram endpoint hands out. It works when the
URL is supplied another way:

  * a page that exposes a direct media URL, or
  * a future Instagram Business/Graph API integration for linked accounts, or
  * your own resolver, behind the same interface.

The point of putting it here is that it is the shape a *real* provider takes, and it
requires no changes to the pipeline, the LLM step, the API, or the app.

Speech-to-text runs on Groq, so it reuses GROQ_API_KEY. Note the model name: `whisper-1`
is OpenAI's name and does not exist on Groq; the live equivalents are
`whisper-large-v3` and `whisper-large-v3-turbo`.

Deliberately not included: HTML scraping for the `video_url`, and logged-in session
reuse. Both are ToS violations and neither would survive a Play review.
"""

from __future__ import annotations

import asyncio
import logging
import urllib.error
import urllib.request
from pathlib import Path
from typing import ClassVar

from app.config import Settings, get_settings
from app.providers.base import (
    ProviderError,
    ProviderUnavailable,
    TranscriptProvider,
    TranscriptResult,
)
from app.providers.oembed import OEmbedProvider

logger = logging.getLogger(__name__)


class WhisperProvider(TranscriptProvider):
    name: ClassVar[str] = "whisper"
    description: ClassVar[str] = "Speech-to-text over reel audio (needs a resolvable media URL)"

    def __init__(self, settings: Settings | None = None) -> None:
        # settings must be optional: providers/registry.py builds every provider with
        # no arguments so it can report availability in GET /v1/health.
        self.settings = settings or get_settings()
        self._oembed = OEmbedProvider(self.settings.oembed_timeout_seconds)

    def is_available(self) -> bool:
        return bool(self.settings.effective_whisper_key)

    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        if not self.is_available():
            raise ProviderUnavailable("no API key for speech-to-text")

        metadata_result = await self._oembed.fetch(reel_url, workspace)
        media_url = metadata_result.metadata.get("media_url")
        if not media_url:
            raise ProviderError(
                "no direct media URL is available for this reel, so the audio cannot "
                "be downloaded. This provider needs a media resolver."
            )

        audio_path = workspace / "audio.m4a"
        await asyncio.to_thread(self._download, media_url, audio_path)
        transcript, language = await asyncio.to_thread(self._transcribe, audio_path)

        # The audio is NOT deleted here. It is returned as media_path so the pipeline
        # can OCR frames from it, and the per-job workspace tears the whole directory
        # down when the job ends - deleting it before OCR would make the file vanish
        # from under the OCR step and silently skip it.
        return TranscriptResult(
            provider=self.name,
            transcript=transcript,
            metadata=metadata_result.metadata,
            hashtags=metadata_result.hashtags,
            creator=metadata_result.creator,
            media_path=audio_path,
            language=language,
            is_partial=False,
        )

    def _download(self, media_url: str, destination: Path) -> None:
        request = urllib.request.Request(media_url, headers={"User-Agent": "ResourceTracker/0.1"})
        try:
            with urllib.request.urlopen(request, timeout=self.settings.download_timeout_seconds) as response:
                destination.write_bytes(response.read(self.settings.whisper_max_bytes))
        except (urllib.error.URLError, TimeoutError) as exc:
            raise ProviderError(f"audio download failed: {exc}") from exc
        if destination.stat().st_size == 0:
            raise ProviderError("audio download produced an empty file")

    def _transcribe(self, audio_path: Path) -> tuple[str, str | None]:
        try:
            from groq import Groq
        except ImportError as exc:  # pragma: no cover
            raise ProviderUnavailable("the groq package is not installed") from exc

        client = Groq(api_key=self.settings.effective_whisper_key)
        try:
            with audio_path.open("rb") as handle:
                completion = client.audio.transcriptions.create(
                    file=(audio_path.name, handle.read()),
                    model=self.settings.whisper_model,
                    response_format="verbose_json",
                )
        except Exception as exc:  # noqa: BLE001
            raise ProviderError(f"speech-to-text failed: {exc}") from exc

        text = (getattr(completion, "text", None) or "").strip()
        if not text:
            raise ProviderError("speech-to-text returned an empty transcript")
        return text, getattr(completion, "language", None)
