"""Tests for the Groq summariser and its offline fallback.

No test here touches the network. The point of these tests is the failure behaviour:
a bad model response must not produce a broken card, and an unconfigured key must
degrade to something honest rather than erroring out.
"""

from __future__ import annotations

import json

import pytest

from app.models import KnowledgeCard
from app.pipeline.summarizer import (
    CardSummarizer,
    _coerce,
    _extractive_fallback,
    _merge_source_metadata,
    build_prompt,
    extract_json,
)
from app.providers.base import CreatorProfile, TranscriptResult, extract_hashtags

TRANSCRIPT = (
    "The three habits are: sleep before 10pm, run before breakfast, and read twenty "
    "pages. Sleep was the biggest one. Running before breakfast beat every evening run "
    "I ever tried. Reading twenty pages sounds small but it compounds."
)


def test_extract_json_handles_a_plain_object():
    assert extract_json('{"title": "A", "summary": "B"}') == {"title": "A", "summary": "B"}


def test_extract_json_unwraps_a_code_fence():
    raw = 'Sure!\n```json\n{"title": "A", "summary": "B"}\n```\nHope that helps.'
    assert extract_json(raw)["title"] == "A"


def test_extract_json_finds_an_object_inside_prose():
    raw = 'Here you go: {"title": "A", "summary": "B"} - let me know.'
    assert extract_json(raw)["summary"] == "B"


def test_extract_json_rejects_a_non_object():
    with pytest.raises(ValueError):
        extract_json("[1, 2, 3]")


def test_coerce_normalises_strings_into_lists():
    coerced = _coerce(
        {
            "title": "Habits",
            "summary": "Three habits.",
            "key_insights": "just a string",
            "action_items": ["a", "b"],
            "topics": [],
        }
    )
    assert coerced["key_insights"] == ["just a string"]
    assert coerced["action_items"] == ["a", "b"]


def test_coerce_requires_a_title_and_summary():
    with pytest.raises(ValueError, match="no title"):
        _coerce({"summary": "s"})
    with pytest.raises(ValueError, match="no summary"):
        _coerce({"title": "t"})


def test_coerce_strips_and_caps_lengths():
    coerced = _coerce({"title": "  x  " * 200, "summary": "y" * 9000})
    assert len(coerced["title"]) <= 200
    assert len(coerced["summary"]) <= 4000
    assert coerced["title"] == coerced["title"].strip()


def test_coerce_parses_description_and_hashtags():
    coerced = _coerce(
        {
            "title": "Habits",
            "summary": "Three habits.",
            "description": "A short overview. " * 200,
            "hashtags": ["#Sleep", "#Habits", "focus"],
        }
    )
    assert coerced["description"].startswith("A short overview")
    assert len(coerced["description"]) <= 400
    # Stored without the '#', so display can add the marker back.
    assert coerced["hashtags"] == ["Sleep", "Habits", "focus"]


def test_merge_source_metadata_wins_with_provider_facts():
    """Hashtags and creator come from the provider, not the model's guess."""
    card = KnowledgeCard(
        title="t",
        summary="s",
        hashtags=["Sleep", "MadeUpTag"],
        description="model wrote this",
    )
    result = TranscriptResult(
        provider="oembed",
        transcript="",
        hashtags=["sleep", "Habit"],
        creator=CreatorProfile(name="Mark", username="marks", bio="Lives for mornings"),
        metadata={"description": "authoritative caption"},
    )
    merged = _merge_source_metadata(card, result)

    # Case-insensitive union, first-seen casing kept: the provider's verbatim hashtag
    # ('sleep') wins over the model's differently-cased copy ('Sleep'). The model's
    # one-off tag survives because it was never in the provider's list.
    assert merged.hashtags == ["sleep", "Habit", "MadeUpTag"]
    assert merged.creator_username == "marks"
    assert merged.creator_bio == "Lives for mornings"
    # The model's description is a real card field and takes priority over the caption.
    assert merged.description == "model wrote this"


def test_merge_fills_description_from_the_caption_when_missing():
    card = KnowledgeCard(title="t", summary="s")
    result = TranscriptResult(
        provider="oembed",
        transcript="",
        metadata={"description": "heat the top of the pan"},
    )
    assert _merge_source_metadata(card, result).description == "heat the top of the pan"


def test_merge_names_a_contentless_card_after_the_creator():
    """A caption of one emoji leaves no title, and '@marks' beats 'Saved reel'."""
    card = KnowledgeCard(title="Saved reel", summary="s")
    result = TranscriptResult(
        provider="oembed",
        transcript="",
        creator=CreatorProfile(username="marks"),
        metadata={"author": "marks"},
    )
    assert _merge_source_metadata(card, result).title == "@marks"


def test_merge_keeps_a_real_title_even_when_the_creator_is_known():
    card = KnowledgeCard(title="Finishing a side project", summary="s")
    result = TranscriptResult(
        provider="oembed",
        transcript="",
        creator=CreatorProfile(username="marks"),
    )
    assert _merge_source_metadata(card, result).title == "Finishing a side project"


def test_extract_hashtags_deduplicates_across_blocks():
    assert extract_hashtags("Try #Focus and #focus and #sleep #focus", "Also #sleep") == [
        "Focus",
        "sleep",
    ]


def test_knowledge_card_deduplicates_and_trims():
    card = KnowledgeCard(
        title="t",
        summary="s",
        key_insights=["  one  ", "one", "two"],
        action_items=["a"],
        topics=["a"],
    )
    assert card.key_insights == ["one", "two"]


def test_prompt_truncates_an_enormous_transcript():
    result = TranscriptResult(provider="p", transcript="word " * 200_000)
    prompt = build_prompt(result, max_chars=600)
    assert len(prompt) < 2000
    assert "[truncated]" in prompt


def test_prompt_tells_the_model_when_extraction_was_partial():
    thin = TranscriptResult.not_available("p", "caption only")
    thin.metadata["title"] = "A caption"
    assert "extraction was incomplete" in build_prompt(thin)


def test_prompt_never_exceeds_the_token_budget(settings):
    """A pathological input must not be able to blow past the context window."""
    result = TranscriptResult(
        provider="p",
        transcript="a" * 5_000_000,
        captions=["b" * 100_000] * 5,
    )
    assert len(build_prompt(result)) < 20_000


def test_fallback_summarises_without_an_api_key():
    card = _extractive_fallback(TranscriptResult(provider="p", transcript=TRANSCRIPT))
    assert isinstance(card, KnowledgeCard)
    assert card.title
    assert "habits" in card.summary.lower()


def test_fallback_is_honest_when_there_is_no_content():
    thin = TranscriptResult.not_available("p", "nothing")
    thin.metadata["title"] = "Saved reel"
    card = _extractive_fallback(thin)
    assert "No transcript was available" in card.summary


async def test_summarizer_falls_back_when_groq_is_unconfigured(settings):
    summarizer = CardSummarizer(settings)
    assert summarizer.is_configured() is False

    card = await summarizer.summarize(TranscriptResult(provider="p", transcript=TRANSCRIPT))
    assert card.summary


async def test_summarizer_survives_a_failing_model(settings, monkeypatch):
    summarizer = CardSummarizer(settings)
    summarizer.settings = settings.model_copy(update={"groq_api_key": "sk-test"})

    calls = 0

    def _boom(_prompt: str) -> str:
        nonlocal calls
        calls += 1
        raise RuntimeError("model unavailable")

    monkeypatch.setattr(summarizer, "_call_groq", _boom)

    card = await summarizer.summarize(TranscriptResult(provider="p", transcript=TRANSCRIPT))
    assert card.summary, "a dead model should still yield a card"
    assert calls == settings.groq_max_retries + 1, "retries were not attempted"


async def test_summarizer_parses_a_good_model_response(settings, monkeypatch):
    summarizer = CardSummarizer(settings)
    summarizer.settings = settings.model_copy(update={"groq_api_key": "sk-test"})

    payload = {
        "title": "Three habits that stuck",
        "summary": "The creator explains three habits.",
        "description": "Three habits the creator swears by.",
        "hashtags": ["habits", "health"],
        "key_insights": ["Sleep first", "Run early"],
        "action_items": ["Try an earlier bedtime"],
        "topics": ["habits", "health"],
    }
    monkeypatch.setattr(summarizer, "_call_groq", lambda _p: json.dumps(payload))

    card = await summarizer.summarize(TranscriptResult(provider="p", transcript=TRANSCRIPT))
    assert card.title == "Three habits that stuck"
    assert card.key_insights == ["Sleep first", "Run early"]
    assert card.topics == ["habits", "health"]
    assert card.description == "Three habits the creator swears by."
    assert card.hashtags == ["habits", "health"]


async def test_summarizer_merges_hashtags_and_creator_from_the_provider(settings, monkeypatch):
    summarizer = CardSummarizer(settings)
    summarizer.settings = settings.model_copy(update={"groq_api_key": "sk-test"})

    payload = {
        "title": "Thumb rules",
        "summary": "Five rules for a grip.",
        "hashtags": ["#climbing"],
    }
    monkeypatch.setattr(summarizer, "_call_groq", lambda _p: json.dumps(payload))

    result = TranscriptResult(
        provider="oembed",
        transcript=TRANSCRIPT,
        hashtags=["climbing", "bouldering"],
        creator=CreatorProfile(username="thecrev", bio="Tiny crimps only"),
    )
    card = await summarizer.summarize(result)
    assert card.hashtags == ["climbing", "bouldering"]
    assert card.creator_username == "thecrev"
    assert card.creator_bio == "Tiny crimps only"


async def test_fallback_keeps_provider_metadata(settings):
    summarizer = CardSummarizer(settings)
    result = TranscriptResult(
        provider="oembed",
        transcript="",
        metadata={"title": "Morning routine", "description": "Run before breakfast #run"},
        hashtags=["run"],
        creator=CreatorProfile(username="earlybird"),
    )
    card = await summarizer.summarize(result)
    assert card.title == "Morning routine"
    assert card.description == "Run before breakfast #run"
    assert card.hashtags == ["run"]
    assert card.creator_username == "earlybird"


async def test_summarizer_recovers_from_one_bad_then_one_good_response(settings, monkeypatch):
    summarizer = CardSummarizer(settings)
    summarizer.settings = settings.model_copy(update={"groq_api_key": "sk-test"})

    responses = ["not json at all", json.dumps({"title": "Recovered", "summary": "Fine."})]

    def _flaky(_prompt: str) -> str:
        return responses.pop(0)

    monkeypatch.setattr(summarizer, "_call_groq", _flaky)

    card = await summarizer.summarize(TranscriptResult(provider="p", transcript=TRANSCRIPT))
    assert card.title == "Recovered"
    assert not responses, "the second response should never have been needed"
