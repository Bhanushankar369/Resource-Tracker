"""Provider that reads public metadata from X (Twitter) posts.

Uses two approaches:
1. X's oEmbed endpoint (https://publish.twitter.com/oembed) - free, no auth,
   returns the tweet text and author for any public tweet.
2. Syndication API (https://cdn.syndication.twimg.com/tweet-result) - also
   free, returns richer JSON including the full tweet text, media, and metrics.

No API key is needed for either approach. The X API v2 (paid) is NOT used.
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

# Matches tweet/post IDs from X/Twitter URLs:
#   twitter.com/user/status/ID, x.com/user/status/ID
_TWEET_ID = re.compile(
    r"(?:twitter\.com|x\.com)/[A-Za-z0-9_]+/status/(\d+)"
)

_X_HOSTS = {"twitter.com", "www.twitter.com", "x.com", "www.x.com", "mobile.twitter.com"}

_TITLE_WORDS = 8


def extract_tweet_id(url: str) -> str | None:
    """Extracts the numeric tweet ID from a Twitter/X URL."""
    match = _TWEET_ID.search(url)
    return match.group(1) if match else None


def extract_username(url: str) -> str | None:
    """Extracts the username from a Twitter/X URL."""
    match = re.search(
        r"(?:twitter\.com|x\.com)/([A-Za-z0-9_]+)/status/", url
    )
    return match.group(1) if match else None


def is_x_url(url: str) -> bool:
    """True when the URL points to a tweet/post on X (Twitter)."""
    try:
        parsed = urllib.parse.urlparse(url)
        host = (parsed.hostname or "").lower()
        return host in _X_HOSTS and extract_tweet_id(url) is not None
    except Exception:
        return False


def _fetch(url: str, timeout: float, headers: dict[str, str] | None = None) -> bytes:
    request = urllib.request.Request(
        url, headers={"User-Agent": _USER_AGENT, **(headers or {})}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        raise ProviderError(
            f"X/Twitter returned HTTP {exc.code} for {url}"
        ) from exc
    except (urllib.error.URLError, socket.timeout, TimeoutError) as exc:
        raise ProviderError(f"request to X/Twitter failed: {exc}") from exc
    except Exception as exc:  # noqa: BLE001
        raise ProviderError(f"unexpected HTTP failure for X/Twitter: {exc}") from exc


def _strip_html(html: str) -> str:
    """Remove HTML tags from oEmbed HTML response to get plain text."""
    # Remove <a> tags but keep their text content
    text = re.sub(r"<a[^>]*>(.*?)</a>", r"\1", html, flags=re.DOTALL)
    # Remove <blockquote> and other tags
    text = re.sub(r"<[^>]+>", " ", text)
    # Decode HTML entities
    text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    text = text.replace("&quot;", '"').replace("&#39;", "'").replace("&nbsp;", " ")
    # Collapse whitespace
    text = " ".join(text.split()).strip()
    # Remove trailing "— name (@handle) date" attribution that oEmbed adds
    text = re.sub(r"\s*—\s*[^—]+\(@\w+\)\s*\w+\s+\d+,\s+\d+\s*$", "", text)
    return text.strip()


class XTwitterProvider(TranscriptProvider):
    """Public X (Twitter) post metadata via oEmbed + syndication API."""

    name: ClassVar[str] = "x_twitter"
    description: ClassVar[str] = (
        "X (Twitter) post metadata (text, author) via public endpoints"
    )

    def __init__(
        self,
        timeout: float | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self._timeout = timeout or self.settings.oembed_timeout_seconds

    def is_available(self) -> bool:
        # No API key needed for public endpoints.
        return True

    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        tweet_id = extract_tweet_id(reel_url)
        if not tweet_id:
            return TranscriptResult.not_available(
                self.name, "URL does not contain an X/Twitter post ID"
            )

        username = extract_username(reel_url)

        # 1. Try syndication API first (richer data)
        syndication = await self._fetch_syndication(tweet_id)

        # 2. Fall back to oEmbed
        oembed = {}
        if not syndication:
            oembed = await self._fetch_oembed(reel_url)

        # Extract text, author, media from whichever source worked
        tweet_text = ""
        author_name = ""
        author_handle = username or ""
        profile_image = ""

        if syndication:
            tweet_text = syndication.get("text", "")
            user = syndication.get("user", {})
            author_name = user.get("name", "")
            author_handle = user.get("screen_name", username or "")
            profile_image = user.get("profile_image_url_https", "")

            # Get media if present
            media_items = syndication.get("mediaDetails", [])
            if media_items:
                first_media = media_items[0]
                media_url = first_media.get("media_url_https", "")
                if media_url:
                    # Use media as thumbnail
                    profile_image = media_url

        elif oembed:
            html = oembed.get("html", "")
            tweet_text = _strip_html(html)
            author_name = oembed.get("author_name", "")
            author_handle = (
                oembed.get("author_url", "").rstrip("/").rsplit("/", 1)[-1]
                or username or ""
            )

        # Build result
        metadata: dict[str, str] = {}
        if tweet_text:
            metadata["description"] = tweet_text
            # Use first line as title
            first_line = tweet_text.split("\n")[0].strip()
            words = first_line.split()
            if len(words) <= _TITLE_WORDS:
                metadata["title"] = first_line.rstrip(".,;:!?")
            else:
                metadata["title"] = " ".join(words[:_TITLE_WORDS]).rstrip(".,;:!?")
        if author_handle:
            metadata["author"] = author_handle
        if profile_image:
            metadata["thumbnail_url"] = profile_image

        if not metadata:
            return TranscriptResult.not_available(
                self.name, "no metadata could be read for this X/Twitter post"
            )

        creator = None
        if author_name or author_handle:
            creator = CreatorProfile(
                name=author_name or author_handle,
                username=author_handle or None,
            )

        hashtags = extract_hashtags(tweet_text)

        # Tweets are text-native, so the full text is always the content
        is_partial = not tweet_text

        warnings = []
        if is_partial:
            warnings.append(
                "metadata-only extraction: only the author could be determined. "
                "The tweet text was not readable."
            )

        return TranscriptResult(
            provider=self.name,
            transcript=tweet_text,  # Tweet text IS the transcript
            metadata=metadata,
            creator=creator,
            hashtags=hashtags,
            is_partial=is_partial,
            warnings=warnings,
        )

    async def _fetch_syndication(self, tweet_id: str) -> dict:
        """Fetch tweet data from the syndication API (no auth needed)."""
        endpoint = (
            f"https://cdn.syndication.twimg.com/tweet-result?"
            f"{urllib.parse.urlencode({'id': tweet_id, 'token': '0'})}"
        )
        try:
            raw = await asyncio.to_thread(
                _fetch, endpoint, self._timeout, {"Accept": "application/json"}
            )
            payload = json.loads(raw.decode("utf-8", errors="replace"))
            if isinstance(payload, dict) and payload.get("text"):
                return payload
            return {}
        except ProviderError as exc:
            logger.info("Syndication API unavailable: %s", exc)
            return {}
        except json.JSONDecodeError:
            return {}

    async def _fetch_oembed(self, url: str) -> dict:
        """Fetch tweet data from X's oEmbed endpoint (no auth needed)."""
        endpoint = (
            f"https://publish.twitter.com/oembed?"
            f"{urllib.parse.urlencode({'url': url, 'omit_script': 'true'})}"
        )
        try:
            raw = await asyncio.to_thread(
                _fetch, endpoint, self._timeout, {"Accept": "application/json"}
            )
            return json.loads(raw.decode("utf-8", errors="replace"))
        except ProviderError as exc:
            logger.info("X oEmbed unavailable: %s", exc)
            return {}
        except json.JSONDecodeError:
            return {}
