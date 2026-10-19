"""Provider that reads public reel metadata from Meta's Graph oEmbed endpoint.

This is the least capable provider and it is always the last link in the chain. It
cannot read speech or on-screen captions, so it returns `is_partial=True` with the
caption and creator only, and the summariser is instructed to stay strictly within
that.

Why it needs a token
--------------------
Instagram retired every unauthenticated metadata read. Verified against live
requests, all three of the shapes this provider used to try are gone:

  * ``api.instagram.com/oembed?url=...``  -> 302 to ``instagram.com/oembed/`` and
    answers with a 600KB HTML page, so every JSON parse fails.
  * ``/reel/<code>/?__a=1&__d=dis``       -> HTTP 500 "Oops, an error occurred".
  * ``/reel/<code>/embed/``               -> the same login-wall shell, with no
    caption, username or media id anywhere in the markup.

What is left is the Graph oEmbed endpoint, which is documented and stable but
requires a Meta app token - see ``INSTAGRAM_ACCESS_TOKEN`` in config.py. Fetching it
with no token returns OAuth error 190, so this provider reports itself unavailable
rather than pretending to work.

Deliberately not included: reusing a logged-in Instagram session cookie, or scraping
the mobile web bundle. Both are ToS violations, and neither survives a Play review.
"""

from __future__ import annotations

import asyncio
import json
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

_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

_SHORTCODE = re.compile(r"/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)")

#: How many words of the caption become the card title.
_TITLE_WORDS = 8

#: Meta's error codes worth naming in the failure message. The rest are opaque.
_ERROR_HINTS = {
    10: (
        "Meta requires App Review for this endpoint. Until the app is approved, "
        "paste the caption and the creator name when you save the reel"
    ),
    100: "the reel does not exist or is private",
    190: "INSTAGRAM_ACCESS_TOKEN is invalid or has expired",
    200: "the app is not permitted to read this reel",
    803: "the app cannot read this reel until it is live and in review",
}


def extract_shortcode(reel_url: str) -> str | None:
    match = _SHORTCODE.search(urllib.parse.urlparse(reel_url).path)
    return match.group(1) if match else None


def _fetch(url: str, timeout: float, headers: dict[str, str] | None = None) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT, **(headers or {})})
    opener = urllib.request.build_opener()  # no redirect to a different host is enforced
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        # Graph puts the real reason in the JSON body of a 4xx. Dropping it leaves
        # "HTTP 400", which is how App Review (error 10) ends up invisible to the
        # person who has to fix it. The body is echoed back, never logged as detail.
        detail = ""
        try:
            detail = _api_error_detail(exc.read())
        except Exception:  # noqa: BLE001 - the body is a nicety, not a requirement
            detail = ""
        raise ProviderError(
            f"Meta Graph returned HTTP {exc.code}"
            + (f": {detail}" if detail else "")
            + f" for {urlparse_host(url)}"
        ) from exc
    except (urllib.error.URLError, socket.timeout, TimeoutError) as exc:
        raise ProviderError(f"request to {urlparse_host(url)} failed: {exc}") from exc
    except Exception as exc:  # noqa: BLE001
        raise ProviderError(f"unexpected HTTP failure for {urlparse_host(url)}: {exc}") from exc


def _api_error_detail(body: bytes) -> str:
    """One readable line out of Meta's `{"error": {...}}` envelope."""
    try:
        payload = json.loads(body.decode("utf-8", errors="replace"))
    except json.JSONDecodeError:
        return ""
    if not isinstance(payload, dict):
        return ""
    error = payload.get("error")
    if not isinstance(error, dict):
        return ""
    message = _first_str(error.get("message")) or ""
    code = error.get("code")
    hint = _ERROR_HINTS.get(code, "") if isinstance(code, int) else ""
    detail = f"{message} (code {code})" if isinstance(code, int) else message
    return f"{detail}: {hint}" if hint else detail


def urlparse_host(url: str) -> str:
    return urllib.parse.urlparse(url).netloc or url


def username_from_author_url(author_url: str | None) -> str | None:
    """`https://www.instagram.com/someone/` -> `someone`.

    The Graph oEmbed response carries the creator's profile URL but no separate
    username field, and the card wants the handle to show the reel's author.
    """
    if not author_url:
        return None
    handle = urllib.parse.urlparse(author_url).path.strip("/").split("/")[0]
    return handle or None


def title_from_caption(caption: str) -> str | None:
    """A short, non-empty label derived from the caption.

    Graph oEmbed puts the caption in `title` and nothing else, and the pipeline wants
    both: the full caption as the card's description, and something short enough to
    put on a list row. Falls back to the caption itself when it is one short line.
    """
    first_line = next((line.strip() for line in (caption or "").splitlines() if line.strip()), "")
    if not first_line:
        return None
    words = first_line.split()
    if len(words) <= _TITLE_WORDS:
        return first_line.rstrip(".,;:!?")
    return " ".join(words[:_TITLE_WORDS]).rstrip(".,;:!?")


class OEmbedProvider(TranscriptProvider):
    name: ClassVar[str] = "oembed"
    description: ClassVar[str] = "Public reel metadata (caption + creator) via Meta Graph oEmbed"

    def __init__(
        self,
        timeout: float | None = None,
        settings: Settings | None = None,
        access_token: str | None = None,
    ) -> None:
        # settings must be optional: providers/registry.py builds every provider with
        # no arguments so it can report availability in GET /v1/health.
        self.settings = settings or get_settings()
        self._timeout = self.settings.oembed_timeout_seconds if timeout is None else timeout
        self._access_token = (
            self.settings.instagram_access_token if access_token is None else access_token
        )

    def is_available(self) -> bool:
        return bool(self._access_token.strip())

    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        if not self.is_available():
            return TranscriptResult.not_available(
                self.name,
                "INSTAGRAM_ACCESS_TOKEN is not set, so Instagram metadata cannot be read",
            )

        endpoint = self._endpoint(reel_url)
        try:
            payload = await asyncio.to_thread(self._fetch_json, endpoint)
        except ProviderError as exc:
            return TranscriptResult.not_available(self.name, str(exc))

        caption = _first_str(payload.get("title"), _nested(payload, "media", "caption", "text"))
        metadata = {
            key: value
            for key, value in (
                # The caption is the description. It is also the only title-like field
                # Instagram returns, so a short form of it becomes the title.
                ("description", caption),
                ("title", title_from_caption(caption or "")),
                ("author", _first_str(payload.get("author_name"), _nested(payload, "media", "username"))),
                ("author_url", _first_str(payload.get("author_url"))),
                ("thumbnail_url", _first_str(payload.get("thumbnail_url"), payload.get("thumbnail"))),
                ("media_id", _first_str(payload.get("media_id"))),
                ("duration_seconds", _first_str(_nested(payload, "media", "video_duration"))),
            )
            if value
        }
        # A payload that yields none of those carries nothing worth building a card
        # from - surface it as "no record" rather than a half-populated card.
        if not metadata:
            return TranscriptResult.not_available(self.name, "no oEmbed record for this reel")

        return TranscriptResult(
            provider=self.name,
            metadata=metadata,
            creator=self._creator_from([payload]),
            hashtags=extract_hashtags(caption or ""),
            is_partial=True,
            warnings=[
                "metadata-only extraction: this card cannot describe the spoken or "
                "on-screen content of the reel"
            ],
        )

    def _endpoint(self, reel_url: str) -> str:
        query = urllib.parse.urlencode(
            {"url": reel_url, "access_token": self._access_token, "omitscript": "true"}
        )
        version = self.settings.instagram_graph_version.strip() or "v21.0"
        return f"https://graph.facebook.com/{version}/instagram_oembed?{query}"

    def _fetch_json(self, url: str) -> dict:
        raw = _fetch(url, self._timeout, {"Accept": "application/json"})
        try:
            payload = json.loads(raw.decode("utf-8", errors="replace"))
        except json.JSONDecodeError as exc:
            # An HTML body here means Instagram or a proxy answered instead of Graph,
            # which is exactly what an expired app or a missing product looks like.
            raise ProviderError(f"response was not JSON: {exc}") from exc
        if not isinstance(payload, dict):
            raise ProviderError("response was not a JSON object")
        self._raise_for_api_error(payload)
        return payload

    @staticmethod
    def _raise_for_api_error(payload: dict) -> None:
        """Turns Meta's `{"error": {...}}` envelope into a readable ProviderError.

        Left alone this would fall through to "no oEmbed record", which is true but
        useless: a misconfigured token looks exactly like a missing reel unless the
        code is surfaced.
        """
        if not isinstance(payload.get("error"), dict):
            return
        raise ProviderError(_api_error_detail(json.dumps(payload).encode("utf-8")) or "unknown error")

    def _creator_from(self, payloads: list[dict]) -> CreatorProfile | None:
        """Builds a CreatorProfile from whichever public shape an endpoint gave us."""
        for payload in payloads:
            user = _first_dict(_nested(payload, "user"))
            media_user = _first_dict(_nested(payload, "media", "user"))
            if user is None:
                user = media_user
            author_url = _first_str(payload.get("author_url"))
            profile = CreatorProfile(
                # Instagram's oEmbed gives no full name and no bio, only a handle, so
                # the handle is the best name available and the card shows it either way.
                name=_first_str(_nested(user, "full_name")),
                username=_first_str(
                    _nested(user, "username"),
                    _nested(media_user, "username"),
                    _nested(payload, "media", "username"),
                    username_from_author_url(author_url),
                ),
                bio=_first_str(_nested(user, "biography"), _nested(media_user, "biography")),
            )
            if profile.display_name or profile.username:
                return profile
        return None


def _first_str(*candidates: object) -> str | None:
    for candidate in candidates:
        if isinstance(candidate, str) and candidate.strip():
            return candidate.strip()
        if isinstance(candidate, (int, float)):
            return str(candidate)
    return None


def _first_dict(*candidates: object) -> dict | None:
    for candidate in candidates:
        if isinstance(candidate, dict) and candidate:
            return candidate
    return None


def _nested(payload: object, *path: str) -> object:
    current = payload
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current