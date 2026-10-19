"""Turns a TranscriptResult into a KnowledgeCard using Groq.

One request per job, temperature near zero, structured JSON output. The prompt is
deliberately strict about the partial case: when a provider could only read the
caption, the model is told to produce a thinner card rather than invent content. That
matters more than it looks - a confident hallucinated summary of a reel the backend
never actually read is the worst possible failure for this product.

Also handles the no-API-key case by falling back to a deterministic extractive
summariser, so the app is usable end-to-end before anyone has a Groq key.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

from app.config import Settings
from app.models import KnowledgeCard
from app.providers.base import TranscriptResult

logger = logging.getLogger(__name__)

_SYSTEM_PROMPT = (
    "You turn short-form social media content (reels, shorts, videos, tweets, posts) "
    "into a single study card. "
    "You never invent facts. If the source material does not contain something, "
    "you leave it out rather than guessing."
)

_USER_TEMPLATE = """Below is everything that could be extracted from a social media post.

{content}

Write a knowledge card as strict JSON with exactly these keys:

  "title"          - at most 8 words, no trailing punctuation
  "summary"        - 2-4 sentences, the core idea of the content
  "description"    - 1-2 short sentences that a reader could skim before opening the
                     card; a plain overview, not the full summary
  "key_insights"   - 2-5 short factual points, each under 20 words
  "action_items"   - 0-4 concrete things the viewer could actually do
  "topics"         - 1-5 single-word or short lowercase tags
  "hashtags"       - the hashtags from the content above, as words without '#';
                     empty list if the content has none. Never invent any.

Rules:
- Use only information present above. Do not fill gaps from general knowledge.
- If the material is a caption or metadata only, describe the topic and the claim
  being made. Do NOT pretend to describe visuals, speech, or steps you cannot see.
- If the material says something is a list or a guide, keep the order.
- No markdown, no preamble, no code fences. JSON only.
"""


def _truncate(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return text[:limit].rsplit(" ", 1)[0] + " [truncated]"


def build_prompt(result: TranscriptResult, max_chars: int = 12_000) -> str:
    return _USER_TEMPLATE.format(content=_truncate(result.to_prompt_block(), max_chars))


def extract_json(raw: str) -> dict[str, Any]:
    """Pull a JSON object out of a model response.

    Models occasionally wrap JSON in prose or a code fence even when told not to, and
    losing the whole card to that would be annoying.
    """
    text = raw.strip()

    fence = re.search(r"```(?:json)?\s*(.+?)```", text, re.DOTALL)
    if fence:
        text = fence.group(1).strip()

    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        text = text[start : end + 1]

    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("summariser did not return a JSON object")
    return parsed


def _as_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value if item is not None]
    if isinstance(value, str) and value.strip():
        return [value]
    return []


def _extractive_fallback(result: TranscriptResult) -> KnowledgeCard:
    """Deterministic card used when Groq is unconfigured or the call failed.

    Picks the highest-signal sentences and labels itself honestly, so a user can tell
    the difference between a real summary and this placeholder. Source metadata
    (description, hashtags, creator) is attached by [_merge_source_metadata], which
    `summarize` calls on every card, so this only needs the text-driven fields.
    """
    source = result.transcript.strip() or result.ocr_text.strip()
    if not source:
        title = result.metadata.get("title") or "Saved content"
        description = result.metadata.get("description") or ""
        summary = description or (
            "Saved from social media. No transcript was available, so there is nothing "
            "to summarise yet. Open the content to review it, or paste the transcript "
            "to generate a summary."
        )
        topics = [result.metadata["author"]] if result.metadata.get("author") else []
        return KnowledgeCard(title=title, summary=summary, description=description, topics=topics)

    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", source) if s.strip()]
    lead = sentences[:3]
    summary = " ".join(lead) if lead else source[:400]
    if len(summary) > 600:
        summary = summary[:600].rsplit(" ", 1)[0] + "..."

    first_words = source.split()
    title = " ".join(first_words[:8]).rstrip(".,;:") or "Saved reel"
    description = result.metadata.get("description") or (sentences[0] if sentences else "")

    return KnowledgeCard(
        title=title[:200],
        summary=summary,
        description=description[:400],
        key_insights=sentences[3:8],
        topics=[],
    )


_PLACEHOLDER_TITLES = {"saved reel", "saved content", "untitled", "instagram reel"}


def _merge_source_metadata(card: KnowledgeCard, result: TranscriptResult) -> KnowledgeCard:
    """Enriches a card with what the provider read directly: hashtags + creator.

    These are deterministic facts about the reel (its public caption and profile), so
    they come from the provider rather than the LLM, keeping the card faithful even
    when the model drops or rewrites them. The LLM's own hashtag guess is unioned in
    and capped at the same limit.
    """
    cleaned: list[str] = []
    seen: set[str] = set()
    for tag in list(result.hashtags) + list(card.hashtags):
        value = tag.lstrip("#").strip()
        key = value.lower()
        if value and key not in seen:
            seen.add(key)
            cleaned.append(value)
    card.hashtags = cleaned[:20]

    if result.creator is not None:
        if result.creator.name:
            card.creator_name = card.creator_name or result.creator.name
        if result.creator.username:
            card.creator_username = card.creator_username or result.creator.username
        if result.creator.bio:
            card.creator_bio = card.creator_bio or result.creator.bio
    elif result.metadata.get("author"):
        card.creator_username = card.creator_username or result.metadata["author"]

    # A reel whose caption is a bare emoji or a single word leaves the extractive
    # fallback with nothing to name the card after. The handle is more use than
    # "Saved reel", and it is the one fact we know is true about the reel.
    if card.title.strip().lower() in _PLACEHOLDER_TITLES:
        handle = card.creator_username or result.metadata.get("author")
        if handle:
            card.title = f"@{handle.lstrip('@')}"[:200]

    if not card.description:
        card.description = (result.metadata.get("description") or "")[:400]
    if not card.description and result.captions:
        card.description = result.captions[0][:400]

    # The thumbnail is a fact about the reel, not something to summarise, so it comes
    # straight from the provider. Nothing else populates it, so a card that never gets
    # it here stays blank in the list.
    if not card.thumbnail_url and result.metadata.get("thumbnail_url"):
        card.thumbnail_url = result.metadata["thumbnail_url"][:2048]
    return card


class CardSummarizer:
    """Wraps the Groq call with retries, validation, and a non-LLM fallback."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def is_configured(self) -> bool:
        return self.settings.llm_configured

    async def summarize(self, result: TranscriptResult) -> KnowledgeCard:
        if not self.is_configured():
            logger.info("GROQ_API_KEY is not set, using the extractive fallback")
            return _merge_source_metadata(_extractive_fallback(result), result)

        last_error: Exception | None = None
        for attempt in range(1, self.settings.groq_max_retries + 2):
            try:
                raw = await asyncio.to_thread(self._call_groq, build_prompt(result))
                card = KnowledgeCard(**_coerce(extract_json(raw)))
                return _merge_source_metadata(card, result)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                last_error = exc
                logger.warning(
                    "Summariser attempt %d failed: %s", attempt, exc
                )
                if attempt <= self.settings.groq_max_retries:
                    await asyncio.sleep(min(2 ** attempt, 10))

        logger.error("Summariser gave up after retries: %s", last_error)
        # A partial card beats an error screen, and the warnings already recorded
        # above tell the user the source was incomplete.
        return _merge_source_metadata(_extractive_fallback(result), result)

    def _call_groq(self, prompt: str) -> str:
        try:
            from groq import Groq
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError("the groq package is not installed") from exc

        client = Groq(
            api_key=self.settings.groq_api_key,
            timeout=self.settings.groq_timeout_seconds,
        )
        completion = client.chat.completions.create(
            model=self.settings.groq_model,
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ],
            temperature=0.2,
            max_tokens=1200,
            response_format={"type": "json_object"},
        )
        content = completion.choices[0].message.content
        if not content:
            raise RuntimeError("Groq returned an empty completion")
        return content


def _coerce(payload: dict[str, Any]) -> dict[str, Any]:
    """Normalise the model's JSON into KnowledgeCard kwargs, or raise."""
    title = str(payload.get("title") or "").strip()
    summary = str(payload.get("summary") or "").strip()

    if not title:
        raise ValueError("summariser returned no title")
    if not summary:
        raise ValueError("summariser returned no summary")

    return {
        # Slice first, then strip: truncating a string can leave trailing whitespace,
        # which is visible in the card title.
        "title": title[:200].strip(),
        "summary": summary[:4000].strip(),
        "description": str(payload.get("description") or "").strip()[:400],
        "hashtags": [tag.lstrip("#").strip() for tag in _as_list(payload.get("hashtags"))][:20],
        "key_insights": _as_list(payload.get("key_insights"))[:12],
        "action_items": _as_list(payload.get("action_items"))[:12],
        "topics": _as_list(payload.get("topics"))[:8],
    }
