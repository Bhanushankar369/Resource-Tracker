"""Builds a card from nothing but the link, the caption, and the creator.

This exists because the premise of the provider chain is broken for Instagram.
Verified against live requests in 2026:

  * every unauthenticated metadata endpoint Instagram ever shipped is dead
  * ``instagram.com`` serves an ``httpErrorPage`` shell to every non-browser client,
    for valid reels and invalid ones alike, so there is nothing to scrape
  * Graph's ``instagram_oembed`` works but is gated behind Meta App Review
    ("Meta oEmbed Read", OAuth error 10) until the app is approved

That leaves one source of reel text that always works: whatever the user's own
device already has. Instagram's share sheet sends the URL, and the text around it
is sometimes the caption. The app passes both down, and this module turns them into
a real [TranscriptResult] instead of failing the job.

The result is always partial and says so, so the summariser is told not to invent
speech, visuals or steps. A thin honest card beats an error screen: the user gets
the caption, the creator, and a working link to the reel.
"""

from __future__ import annotations

import re

from app.providers.base import CreatorProfile, TranscriptResult, extract_hashtags
from app.providers.oembed import extract_shortcode, title_from_caption
from app.providers.youtube import extract_video_id, is_youtube_url
from app.providers.x_twitter import extract_tweet_id, is_x_url, extract_username

#: A link, stopping before any separator that belongs to the surrounding sentence.
#: Share sheets append "· 4 likes" to the link; without excluding those the match
#: carries the separator into the caption and it is no longer at the end of the
#: string, so the counter strip below cannot fire.
_URL = re.compile(r"""https?://[^\s"'<>\\]*[^\s"'<>\\·•|.,;:!?]""", re.IGNORECASE)

#: Words Instagram's share sheet appends that are not part of the caption.
_SHARE_NOISE = re.compile(
    r"(?:^|\s)(?:shared via instagram|view instagram posts|open instagram|"
    r"see more on instagram|more on instagram|on instagram)\b\.?",
    re.IGNORECASE,
)

#: Trailing counters the share sheet appends, e.g. "· 1.2K likes · 84 comments".
#: The leading ``\s*`` is load-bearing: a real share has a space between counters,
#: and without it the group cannot repeat, so only the last counter is stripped.
_TRAILING_STATS = re.compile(
    r"(?:\s*[·•|]\s*\d[\d,.]*\s*[KkMm]?\s*(?:likes?|comments?|views?|shares?|replies?))+\s*$",
    re.IGNORECASE,
)


def clean_shared_caption(shared_text: str | None) -> str:
    """Pulls the human-written part out of a share-sheet text blob.

    Instagram shares like ``"Nice one https://instagram.com/reel/X/ · 4 likes ·
    Shared via Instagram"``. This drops the link and the parts that are never
    caption, and collapses the whitespace that is left.

    The Android client runs the equivalent of this before uploading, so in normal
    operation the server is handed an already-cleaned caption. This stays as the
    authoritative implementation for anything that reaches the backend as raw shared
    text.
    """
    if not shared_text:
        return ""
    without_url = _URL.sub(" ", shared_text)
    without_noise = _SHARE_NOISE.sub(" ", without_url)
    without_stats = _TRAILING_STATS.sub("", without_noise)
    return " ".join(without_stats.split()).strip(" ·-–—|")


def build_link_result(
    reel_url: str,
    description: str | None = None,
    creator: str | None = None,
    warnings: list[str] | None = None,
) -> TranscriptResult:
    """A metadata-only result built from the inputs, always non-empty.

    [description] is the caption. [creator] is the posting account, stored without
    its leading ``@`` so the UI decides how to render it.
    """
    caption = (description or "").strip()
    handle = (creator or "").strip().lstrip("@").strip()
    # Identify the platform for better fallback titles
    shortcode = extract_shortcode(reel_url)
    video_id = extract_video_id(reel_url)
    tweet_id = extract_tweet_id(reel_url)

    # The title has to be something a list row can show. Order of preference:
    # the caption's own opening words, then the creator's handle, then a
    # platform-specific identifier.
    title = title_from_caption(caption) or (f"@{handle}" if handle else None)
    if not title:
        if shortcode:
            title = f"Reel {shortcode}"
        elif video_id:
            title = f"YouTube {video_id}"
        elif tweet_id:
            title = f"Post {tweet_id}"

    metadata: dict[str, str] = {}
    if title:
        metadata["title"] = title
    if caption:
        metadata["description"] = caption
    if handle:
        metadata["author"] = handle
    if shortcode:
        metadata["shortcode"] = shortcode

    # Build platform-aware warning message
    if is_youtube_url(reel_url):
        platform_note = (
            "link-only extraction: this card is built from the caption and creator you "
            "provided. The YouTube video's full content could not be read."
        )
    elif is_x_url(reel_url):
        platform_note = (
            "link-only extraction: this card is built from the caption and creator you "
            "provided. The X/Twitter post's full content could not be read."
        )
    else:
        platform_note = (
            "link-only extraction: this card is built from the caption and creator you "
            "provided. The content's spoken or on-screen details could not be read."
        )
    all_warnings = [platform_note]
    all_warnings.extend(warnings or [])

    return TranscriptResult(
        provider="link",
        metadata=metadata,
        creator=CreatorProfile(username=handle or None, name=handle or None) if handle else None,
        hashtags=extract_hashtags(caption),
        is_partial=True,
        warnings=all_warnings,
    )
