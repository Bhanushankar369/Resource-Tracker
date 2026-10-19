"""Provider that reads public metadata from YouTube videos and Shorts.

Uses YouTube's oEmbed endpoint (https://www.youtube.com/oembed) which is free,
unauthenticated, and returns the title, author, and thumbnail for any public
video. No API key is needed.

For richer metadata (description, tags, duration), the provider optionally
uses the YouTube Data API v3 when YOUTUBE_API_KEY is configured. The oEmbed
data alone is enough for a usable card.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import socket
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import ClassVar

from app.config import Settings, get_settings
from app.providers.base import (
    CreatorProfile,
    ProviderError,
    TranscriptProvider,
    TranscriptResult,
    extract_hashtags,
)

logger = logging.getLogger(__name__)

_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

# Matches YouTube video IDs from various URL shapes:
#   youtube.com/watch?v=ID, youtu.be/ID, youtube.com/shorts/ID,
#   youtube.com/embed/ID, youtube.com/v/ID
_YOUTUBE_ID = re.compile(
    r"(?:youtube\.com/(?:watch\?.*?v=|shorts/|embed/|v/)|youtu\.be/)"
    r"([A-Za-z0-9_-]{11})"
)

_YOUTUBE_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"}

_TITLE_WORDS = 8


def extract_video_id(url: str) -> str | None:
    """Extracts the 11-char YouTube video ID from any recognised URL shape."""
    match = _YOUTUBE_ID.search(url)
    return match.group(1) if match else None


def is_youtube_url(url: str) -> bool:
    """True when the URL points to a YouTube video or Short."""
    try:
        parsed = urllib.parse.urlparse(url)
        host = (parsed.hostname or "").lower()
        return host in _YOUTUBE_HOSTS and extract_video_id(url) is not None
    except Exception:
        return False


def title_from_text(text: str) -> str | None:
    """A short title derived from a longer string."""
    first_line = next(
        (line.strip() for line in (text or "").splitlines() if line.strip()), ""
    )
    if not first_line:
        return None
    words = first_line.split()
    if len(words) <= _TITLE_WORDS:
        return first_line.rstrip(".,;:!?")
    return " ".join(words[:_TITLE_WORDS]).rstrip(".,;:!?")


def _fetch(url: str, timeout: float, headers: dict[str, str] | None = None) -> bytes:
    request = urllib.request.Request(
        url, headers={"User-Agent": _USER_AGENT, **(headers or {})}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        raise ProviderError(
            f"YouTube returned HTTP {exc.code} for {url}"
        ) from exc
    except (urllib.error.URLError, socket.timeout, TimeoutError) as exc:
        raise ProviderError(f"request to YouTube failed: {exc}") from exc
    except Exception as exc:  # noqa: BLE001
        raise ProviderError(f"unexpected HTTP failure for YouTube: {exc}") from exc


class YouTubeProvider(TranscriptProvider):
    """Public YouTube video/Shorts metadata via oEmbed + optional Data API v3."""

    name: ClassVar[str] = "youtube"
    description: ClassVar[str] = (
        "YouTube video/Shorts metadata (title, description, creator) via oEmbed"
    )

    def __init__(
        self,
        timeout: float | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self._timeout = timeout or self.settings.oembed_timeout_seconds
        self._api_key = getattr(self.settings, "youtube_api_key", "")

    def is_available(self) -> bool:
        # oEmbed needs no key, so this provider is always available.
        return True

    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        video_id = extract_video_id(reel_url)
        if not video_id:
            return TranscriptResult.not_available(
                self.name, "URL does not contain a YouTube video ID"
            )

        # 1. oEmbed (always works, no key needed)
        oembed = await self._fetch_oembed(reel_url)

        # 2. Data API v3 (optional, needs YOUTUBE_API_KEY)
        data_api = {}
        if self._api_key:
            try:
                data_api = await self._fetch_data_api(video_id)
            except ProviderError as exc:
                logger.warning("YouTube Data API call failed, continuing with oEmbed: %s", exc)

        # Merge: Data API wins where available, oEmbed is fallback
        title = data_api.get("title") or oembed.get("title") or ""
        author = data_api.get("channelTitle") or oembed.get("author_name") or ""
        description = data_api.get("description") or ""
        thumbnail = (
            data_api.get("thumbnail")
            or oembed.get("thumbnail_url")
            or f"https://img.youtube.com/vi/{video_id}/hqdefault.jpg"
        )
        tags = data_api.get("tags") or []
        duration = data_api.get("duration") or ""

        metadata: dict[str, str] = {}
        if title:
            metadata["title"] = title
        if description:
            metadata["description"] = description
        if author:
            metadata["author"] = author
        if thumbnail:
            metadata["thumbnail_url"] = thumbnail
        if duration:
            metadata["duration_seconds"] = duration

        if not metadata:
            return TranscriptResult.not_available(
                self.name, "no metadata returned for this YouTube video"
            )

        hashtags = extract_hashtags(description, " ".join(tags))
        creator = CreatorProfile(name=author, username=author) if author else None

        # With only oEmbed we have title + author but no description/transcript,
        # so mark as partial. With Data API description we have more to work with.
        is_partial = not description

        warnings = []
        if is_partial:
            warnings.append(
                "metadata-only extraction: this card is built from the video title "
                "and author. Configure YOUTUBE_API_KEY for richer descriptions."
            )

        return TranscriptResult(
            provider=self.name,
            metadata=metadata,
            creator=creator,
            hashtags=hashtags,
            is_partial=is_partial,
            warnings=warnings,
        )

    async def _fetch_oembed(self, url: str) -> dict:
        """Fetch metadata from YouTube's free oEmbed endpoint."""
        endpoint = (
            f"https://www.youtube.com/oembed?"
            f"{urllib.parse.urlencode({'url': url, 'format': 'json'})}"
        )
        try:
            raw = await asyncio.to_thread(
                _fetch, endpoint, self._timeout, {"Accept": "application/json"}
            )
            return json.loads(raw.decode("utf-8", errors="replace"))
        except ProviderError:
            return {}
        except json.JSONDecodeError:
            return {}

    async def _fetch_data_api(self, video_id: str) -> dict:
        """Fetch richer metadata from YouTube Data API v3."""
        params = urllib.parse.urlencode({
            "part": "snippet,contentDetails",
            "id": video_id,
            "key": self._api_key,
        })
        endpoint = f"https://www.googleapis.com/youtube/v3/videos?{params}"
        raw = await asyncio.to_thread(
            _fetch, endpoint, self._timeout, {"Accept": "application/json"}
        )
        payload = json.loads(raw.decode("utf-8", errors="replace"))

        items = payload.get("items", [])
        if not items:
            return {}

        snippet = items[0].get("snippet", {})
        content_details = items[0].get("contentDetails", {})

        # Parse ISO 8601 duration (PT1H2M3S) to seconds
        duration_str = content_details.get("duration", "")
        duration_seconds = _parse_iso_duration(duration_str)

        # Pick best thumbnail
        thumbnails = snippet.get("thumbnails", {})
        thumbnail = (
            thumbnails.get("maxres", {}).get("url")
            or thumbnails.get("high", {}).get("url")
            or thumbnails.get("medium", {}).get("url")
            or thumbnails.get("default", {}).get("url")
        )

        result: dict[str, object] = {
            "title": snippet.get("title", ""),
            "description": snippet.get("description", ""),
            "channelTitle": snippet.get("channelTitle", ""),
            "tags": snippet.get("tags", []),
        }
        if thumbnail:
            result["thumbnail"] = thumbnail
        if duration_seconds:
            result["duration"] = str(duration_seconds)

        return result


def _parse_iso_duration(iso: str) -> int | None:
    """Parse ISO 8601 duration like PT1H2M3S to total seconds."""
    if not iso:
        return None
    match = re.match(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", iso)
    if not match:
        return None
    hours = int(match.group(1) or 0)
    minutes = int(match.group(2) or 0)
    seconds = int(match.group(3) or 0)
    total = hours * 3600 + minutes * 60 + seconds
    return total if total > 0 else None
