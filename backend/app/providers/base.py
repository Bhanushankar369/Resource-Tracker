"""The transcript provider abstraction.

This is the seam that makes the whole thing swappable. Everything downstream of a
provider - OCR, the Groq call, the database, the Android app, the reminder - is
written against [TranscriptResult] and does not know or care where the text came
from.

To add a vendor:

  1. subclass [TranscriptProvider]
  2. implement [is_available] and [fetch]
  3. register it in app/providers/registry.py
  4. set TRANSCRIPT_PROVIDER=<its name> in backend/.env

Nothing else in the codebase changes.

WHY THIS IS AN INTERFACE AT ALL
-------------------------------
There is no public API that turns an Instagram reel URL into a downloadable video.
Instagram's Graph API only exposes media for Business/Creator accounts linked to a
Page the caller owns, and even then not as a raw mp4. Scraping violates the Terms
of Use and gets apps rejected. So the URL alone is often not enough, and the
provider is allowed to fail.

What a provider may do when it cannot get a transcript:

  * return [TranscriptResult.not_available] and let the chain fall through
  * raise [ProviderUnavailable] if it is misconfigured (the chain skips it)
  * raise [ProviderError] if the reel is genuinely unreadable (the job fails)
  * return metadata-only text (caption + oEmbed title) with `is_partial=True`, and
    let the LLM summarise what little there is

Because the app can pass `transcript_override`, a user can always unblock a job by
pasting the text themselves.
"""

from __future__ import annotations

import abc
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import ClassVar


class ProviderError(RuntimeError):
    """The provider ran but could not produce a transcript for this reel."""


class ProviderUnavailable(ProviderError):
    """The provider is not configured or its dependencies are missing.

    The registry skips unavailable providers instead of failing the job.
    """


@dataclass
class CreatorProfile:
    """Public profile details of the reel's creator, when a provider can read them."""

    name: str | None = None
    username: str | None = None
    bio: str | None = None

    @property
    def display_name(self) -> str:
        return self.name or self.username or ""


_HASHTAG = re.compile(r"#([A-Za-z0-9_]+)")


def extract_hashtags(*texts: str) -> list[str]:
    """Collects the hashtags mentioned across text blocks, deduped, order kept.

    Hashtags are returned without the leading '#'. The UI re-adds the marker for
    display, so storage stays symmetric with what the LLM is asked to produce.
    Instagram treats fragments case-insensitively, so #Focus and #focus are the same
    tag; the first casing seen is kept.
    """
    seen: set[str] = set()
    collected: list[str] = []
    for text in texts:
        for match in _HASHTAG.finditer(text or ""):
            tag = match.group(1).strip()
            key = tag.lower()
            if tag and key not in seen:
                seen.add(key)
                collected.append(tag)
    return collected[:20]


@dataclass
class TranscriptResult:
    """Normalised output of a provider.

    Fields map onto what the LLM prompt actually consumes:

      * transcript  - spoken words, if the provider does speech-to-text
      * captions    - on-screen / burned-in captions, a list of distinct strings
      * metadata    - title, author, description, duration, anything public
      * ocr_text    - text read off sampled video frames by the extraction step
      * hashtags    - public hashtags from the caption, without the '#'
      * creator     - the creator's name/username/bio, when publicly readable
      * media_path  - a local copy of the media, if the provider downloaded any.
                      Lives only inside the job workspace and is deleted afterwards.

    Nothing in here is ever written to the database. The pipeline turns this into a
    KnowledgeCard, throws the rest away, and deletes [media_path].
    """

    provider: str
    transcript: str = ""
    captions: list[str] = field(default_factory=list)
    metadata: dict[str, str] = field(default_factory=dict)
    ocr_text: str = ""
    hashtags: list[str] = field(default_factory=list)
    creator: CreatorProfile | None = None
    media_path: Path | None = None
    language: str | None = None
    is_partial: bool = False
    warnings: list[str] = field(default_factory=list)

    @property
    def has_content(self) -> bool:
        """True when there is enough text to build a card from."""
        return bool(
            self.transcript.strip()
            or self.captions
            or self.ocr_text.strip()
            or self.metadata.get("title")
            or self.metadata.get("description")
        )

    @classmethod
    def not_available(cls, provider: str, reason: str) -> "TranscriptResult":
        return cls(provider=provider, is_partial=True, warnings=[reason])

    def to_prompt_block(self) -> str:
        """Renders everything into the text block handed to the LLM.

        Kept here rather than in the summariser so that every provider contributes
        to the prompt in the same shape.
        """
        parts: list[str] = []

        if self.creator is not None and (self.creator.display_name or self.creator.bio):
            shown = self.creator.display_name
            if self.creator.username:
                shown += f" (@{self.creator.username})"
            parts.append(f"CREATOR: {shown}")
            if self.creator.bio:
                parts.append(f"CREATOR BIO: {self.creator.bio}")

        if self.metadata.get("title"):
            parts.append(f"CONTENT TITLE: {self.metadata['title']}")
        if self.metadata.get("author"):
            parts.append(f"AUTHOR: {self.metadata['author']}")
        if self.metadata.get("description"):
            parts.append(f"CONTENT DESCRIPTION: {self.metadata['description']}")
        if self.hashtags:
            parts.append("HASHTAGS: " + " ".join(f"#{tag}" for tag in self.hashtags))

        if self.transcript.strip():
            parts.append(f"SPOKEN TRANSCRIPT:\n{self.transcript.strip()}")

        if self.captions:
            joined = "\n".join(f"- {caption}" for caption in self.captions)
            parts.append(f"ON-SCREEN CAPTIONS:\n{joined}")

        if self.ocr_text.strip():
            parts.append(f"TEXT FOUND IN THE VIDEO FRAMES (OCR):\n{self.ocr_text.strip()}")

        if not parts:
            parts.append("NO CONTENT COULD BE EXTRACTED FROM THIS POST.")

        if self.is_partial:
            parts.append(
                "NOTE: extraction was incomplete. Base the card only on what is above "
                "and do not invent detail."
            )
        return "\n\n".join(parts)


class TranscriptProvider(abc.ABC):
    """Interface every transcript source implements."""

    #: Stable identifier used in .env and in the database.
    name: ClassVar[str] = "abstract"

    #: Shown in GET /v1/health so the app's Settings tab can list options.
    description: ClassVar[str] = ""

    @abc.abstractmethod
    def is_available(self) -> bool:
        """Whether this provider is configured and its dependencies are importable.

        Called before every job, so it must be cheap and must not raise.
        """

    @abc.abstractmethod
    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        """Produce a [TranscriptResult] for [reel_url].

        [workspace] is a private, job-scoped directory. Any file written there is
        deleted by the pipeline when the job ends, whether it succeeded or not.
        Implementations may therefore download media here without thinking about
        cleanup.

        Raises:
            ProviderUnavailable: this provider cannot run at all.
            ProviderError: it ran but could not read this particular reel.
        """

    def describe(self) -> dict[str, object]:
        return {
            "name": self.name,
            "description": self.description or (self.__doc__ or "").strip().split("\n")[0],
        }
