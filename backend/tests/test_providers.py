"""Tests for the TranscriptProvider abstraction and the fallback chain.

The chain is the extensibility mechanism of the whole product, so these tests pin the
behaviour a future provider author can rely on: unavailable providers are skipped
rather than fatal, one failing provider does not mask a working one, and a
partial-but-nonempty result is accepted rather than discarded.
"""

from __future__ import annotations

import pytest

from app.providers import registry
from app.providers.base import (
    CreatorProfile,
    ProviderError,
    ProviderUnavailable,
    TranscriptProvider,
    TranscriptResult,
    extract_hashtags,
)
from app.providers.manual_paste import ManualPasteProvider
from app.providers.oembed import (
    OEmbedProvider,
    extract_shortcode,
    title_from_caption,
    username_from_author_url,
)
from app.providers.registry import build_provider, run_provider_chain


class _Stub(TranscriptProvider):
    """Base for the generated stubs below. Subclasses carry their own behaviour."""

    name = "stub"
    description = "stub"
    calls = 0

    def is_available(self) -> bool:
        return True

    async def fetch(self, reel_url, workspace):
        type(self).calls += 1
        raise NotImplementedError


# Call counts per registered stub name, reset by the register_stub fixture. A closure
# counter is not reachable from the assertion helpers, so it is mirrored here.
_CALLS: dict[str, int] = {}


@pytest.fixture
def register_stub(monkeypatch):
    """Register stubs under names, each with independent behaviour.

    Returns a `register(name, available=..., result=..., error=...)` function that
    builds a *fresh subclass* per name. Sharing one class across two names would make
    `available`/`result` collide, which silently defeats the point of these tests.
    """
    monkeypatch.setattr(registry, "_PROVIDER_TYPES", dict(registry._PROVIDER_TYPES))
    _CALLS.clear()

    def _register(
        name: str,
        available: bool = True,
        result: TranscriptResult | None = None,
        error: Exception | None = None,
    ) -> type:
        _CALLS[name] = 0

        def _available(self) -> bool:
            return available

        async def _fetch(self, reel_url, workspace):
            _CALLS[name] += 1
            if error is not None:
                raise error
            return result if result is not None else TranscriptResult(provider=name, transcript="")

        # Built with type() rather than a class statement: a class body does not see
        # enclosing function locals, so `name = name` would raise NameError.
        _Configured = type(
            f"Stub_{name}",
            (_Stub,),
            {
                "name": name,
                "description": f"stub {name}",
                "is_available": _available,
                "fetch": _fetch,
            },
        )

        registry._PROVIDER_TYPES[name] = _Configured
        return _Configured

    return _register


def _call_count(name: str) -> int:
    return _CALLS.get(name, 0)


@pytest.fixture
def workspace(settings):
    return settings.workspace_dir


async def test_first_provider_with_content_wins(register_stub, workspace):
    register_stub("first", result=TranscriptResult(provider="first", transcript="the good stuff"))

    result = await run_provider_chain(["first"], "https://instagram.com/reel/abc/", workspace)
    assert result.transcript == "the good stuff"
    assert _call_count("first") == 1


async def test_unavailable_provider_is_skipped_not_fatal(register_stub, workspace):
    register_stub("down", available=False)

    with pytest.raises(ProviderError) as excinfo:
        await run_provider_chain(["down"], "https://instagram.com/reel/abc/", workspace)

    # Skipped, so fetch was never attempted.
    assert _call_count("down") == 0
    assert "not configured" in str(excinfo.value)


async def test_falls_through_to_the_next_provider(register_stub, workspace):
    register_stub("primary", available=False)
    register_stub("secondary", result=TranscriptResult(provider="secondary", transcript="from the backup"))

    result = await run_provider_chain(["primary", "secondary"], "https://x/reel/1/", workspace)
    assert result.provider == "secondary"
    assert "primary: not configured" in " ".join(result.warnings)


async def test_provider_error_falls_through(register_stub, workspace):
    register_stub("broken", error=ProviderError("reel is private"))
    register_stub("working", result=TranscriptResult(provider="working", transcript="fine"))

    result = await run_provider_chain(["broken", "working"], "https://x/reel/1/", workspace)
    assert result.provider == "working"
    assert any("reel is private" in w for w in result.warnings)


async def test_unexpected_provider_exception_does_not_kill_the_job(register_stub, workspace):
    register_stub("exploding", error=ZeroDivisionError("whoops"))
    register_stub("resilient", result=TranscriptResult(provider="resilient", transcript="still here"))

    result = await run_provider_chain(["exploding", "resilient"], "https://x/reel/1/", workspace)
    assert result.provider == "resilient"
    assert any("unexpected error" in w for w in result.warnings)


async def test_partial_result_is_accepted(register_stub, workspace):
    thin = TranscriptResult.not_available("thin", "no captions")
    thin.metadata["title"] = "Three habits of focused people"
    register_stub("thin", result=thin)

    result = await run_provider_chain(["thin"], "https://x/reel/1/", workspace)
    assert result.has_content is True
    assert result.is_partial is True


async def test_empty_chain_raises_a_helpful_error(workspace):
    with pytest.raises(ProviderError, match="No transcript provider is configured"):
        await run_provider_chain([], "https://x/reel/1/", workspace)


async def test_unknown_provider_name_does_not_stop_a_working_one(register_stub, workspace):
    register_stub("real", result=TranscriptResult(provider="real", transcript="ok"))

    result = await run_provider_chain(["typo-in-env", "real"], "https://x/reel/1/", workspace)
    assert result.provider == "real"
    assert any("typo-in-env" in w and "could not be constructed" in w for w in result.warnings)


def test_build_provider_rejects_unknown_names():
    with pytest.raises(KeyError, match="Unknown transcript provider"):
        build_provider("does-not-exist")


async def test_manual_provider_is_always_available(workspace):
    assert ManualPasteProvider().is_available() is True
    empty = await ManualPasteProvider().fetch("https://x/1/", workspace)
    assert empty.has_content is False
    assert "no transcript was supplied" in empty.warnings[0]


async def test_manual_provider_overrides_are_per_instance(workspace):
    """Two concurrent jobs must not see each other's pasted transcript."""
    alice = ManualPasteProvider("alice's private transcript")
    bob = ManualPasteProvider("bob's private transcript")

    assert (await alice.fetch("https://x/1/", workspace)).transcript == "alice's private transcript"
    assert (await bob.fetch("https://x/1/", workspace)).transcript == "bob's private transcript"


async def test_oembed_reports_failure_as_provider_error(workspace, monkeypatch):
    """A network failure must surface as ProviderError, never as a raw socket error."""
    provider = OEmbedProvider(timeout=0.01, access_token="token")

    def _boom(_url, _timeout, _headers=None):
        raise ProviderError("network unreachable")

    monkeypatch.setattr("app.providers.oembed._fetch", _boom)
    result = await provider.fetch("https://www.instagram.com/reel/abc/", workspace)
    assert result.has_content is False
    assert any("network unreachable" in w for w in result.warnings)


def test_oembed_is_unavailable_without_an_instagram_token():
    """No token means no public read, so it must say so rather than look broken."""
    assert OEmbedProvider(access_token="").is_available() is False
    assert OEmbedProvider(access_token="   ").is_available() is False
    assert OEmbedProvider(access_token="abc|def").is_available() is True


async def test_oembed_without_a_token_explains_itself(workspace):
    result = await OEmbedProvider(access_token="").fetch(
        "https://www.instagram.com/reel/abc/", workspace
    )
    assert result.has_content is False
    assert any("INSTAGRAM_ACCESS_TOKEN" in w for w in result.warnings)


async def test_oembed_reads_caption_hashtags_and_creator(workspace, monkeypatch):
    """The whole point of the provider: caption -> description + hashtags, and a handle."""
    provider = OEmbedProvider(access_token="token")
    payload = {
        "title": "Three ways to actually finish a side project #buildinpublic #focus",
        "author_name": "Mark S",
        "author_url": "https://www.instagram.com/marks/",
        "thumbnail_url": "https://example.invalid/thumb.jpg",
        "media_id": "12345",
    }
    monkeypatch.setattr(provider, "_fetch_json", lambda _url: payload)

    result = await provider.fetch("https://www.instagram.com/reel/abc/", workspace)

    assert result.has_content is True
    assert result.is_partial is True
    assert result.metadata["description"].startswith("Three ways to actually finish")
    assert result.metadata["title"] == "Three ways to actually finish a side project"
    assert result.metadata["thumbnail_url"] == "https://example.invalid/thumb.jpg"
    assert result.hashtags == ["buildinpublic", "focus"]
    assert result.creator is not None
    assert result.creator.username == "marks"


async def test_oembed_surfaces_a_graph_api_error(workspace, monkeypatch):
    """An expired token must not be reported as a missing reel."""
    provider = OEmbedProvider(access_token="token")

    def _json_body(_url):
        provider._raise_for_api_error(
            {"error": {"message": "Invalid OAuth access token.", "code": 190}}
        )

    monkeypatch.setattr(provider, "_fetch_json", _json_body)
    result = await provider.fetch("https://www.instagram.com/reel/abc/", workspace)
    assert result.has_content is False
    assert any("INSTAGRAM_ACCESS_TOKEN is invalid" in w for w in result.warnings)


async def test_oembed_reports_a_non_json_body(workspace, monkeypatch):
    """Instagram answering with HTML used to be the default; keep it a clear failure."""
    provider = OEmbedProvider(access_token="token")

    def _html(_url, _timeout, _headers=None):
        return b"<!DOCTYPE html><html>login</html>"

    monkeypatch.setattr("app.providers.oembed._fetch", _html)
    result = await provider.fetch("https://www.instagram.com/reel/abc/", workspace)
    assert result.has_content is False
    assert any("not JSON" in w for w in result.warnings)


def test_oembed_sends_the_token_to_graph():
    provider = OEmbedProvider(access_token="app|secret")
    endpoint = provider._endpoint("https://www.instagram.com/reel/abc/")
    assert endpoint.startswith("https://graph.facebook.com/")
    assert "/instagram_oembed?" in endpoint
    assert "access_token=app%7Csecret" in endpoint
    assert "https%3A%2F%2Fwww.instagram.com%2Freel%2Fabc%2F" in endpoint


@pytest.mark.parametrize(
    "author_url,expected",
    [
        ("https://www.instagram.com/marks/", "marks"),
        ("https://instagram.com/some.one/", "some.one"),
        ("", None),
        (None, None),
    ],
)
def test_username_from_author_url(author_url, expected):
    assert username_from_author_url(author_url) == expected


@pytest.mark.parametrize(
    "caption,expected",
    [
        ("A short caption", "A short caption"),
        ("one two three four five six seven eight nine ten", "one two three four five six seven eight"),
        ("First line here\nsecond line", "First line here"),
        ("   ", None),
    ],
)
def test_title_from_caption(caption, expected):
    assert title_from_caption(caption) == expected


@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://www.instagram.com/reel/Cabc123/", "Cabc123"),
        ("https://instagram.com/p/Short_1-2/", "Short_1-2"),
        ("https://www.instagram.com/tv/AbC_dEf/", "AbC_dEf"),
        ("https://www.instagram.com/stories/someone/123/", None),
    ],
)
def test_extract_shortcode(url, expected):
    assert extract_shortcode(url) == expected


def test_describe_providers_includes_availability():
    described = {entry["name"]: entry for entry in registry.describe_providers()}
    assert "manual" in described
    assert described["manual"]["available"] is True
    # Whisper needs a key that the test environment deliberately does not set.
    assert described["whisper"]["available"] is False


def test_prompt_block_includes_every_source(workspace):
    result = TranscriptResult(
        provider="composed",
        transcript="spoken words here",
        captions=["on screen caption"],
        metadata={"title": "A title", "author": "someone", "description": "a caption"},
        hashtags=["focus", "habits"],
        creator=CreatorProfile(name="Mark", username="marks", bio="Lives for mornings"),
        ocr_text="text burnt into the video",
    )
    block = result.to_prompt_block()
    for expected in (
        "A title",
        "someone",
        "a caption",
        "spoken words here",
        "on screen caption",
        "text burnt into the video",
        "#focus",
        "CREATOR: Mark (@marks)",
        "CREATOR BIO: Lives for mornings",
    ):
        assert expected in block


def test_oembed_extracts_creator_and_hashtags():
    provider = OEmbedProvider()
    creator = provider._creator_from(
        [
            {
                "media": {
                    "user": {"username": "marks", "full_name": "Mark S", "biography": "A bio"},
                    "caption": "Try this #habit and #focus",
                }
            }
        ]
    )
    assert creator is not None
    assert creator.username == "marks"
    assert creator.name == "Mark S"
    assert creator.bio == "A bio"
    assert extract_hashtags("Try this #habit and #focus") == ["habit", "focus"]


def test_provider_chain_always_falls_back_to_oembed(settings):
    chain = settings.provider_chain
    assert chain[0] == "manual"
    assert chain[-1] == "oembed"
    assert len(set(chain)) == len(chain), "the chain must not repeat a provider"


def test_prompt_block_flags_a_partial_extraction():
    block = TranscriptResult.not_available("thin", "no content").to_prompt_block()
    assert "NO CONTENT COULD BE EXTRACTED" in block


from app.providers.youtube import (
    extract_video_id,
    is_youtube_url,
    YouTubeProvider,
)
from app.providers.x_twitter import (
    extract_tweet_id,
    extract_username,
    is_x_url,
    XTwitterProvider,
)
from app.providers.registry import auto_route_chain


@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ"),
        ("https://youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
        ("https://www.youtube.com/shorts/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
        ("https://youtube.com/embed/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
        ("https://example.com/watch?v=dQw4w9WgXcQ", None),
    ],
)
def test_youtube_video_id_and_url_detection(url, expected):
    assert extract_video_id(url) == expected
    assert is_youtube_url(url) == (expected is not None)


@pytest.mark.parametrize(
    "url,expected_id,expected_user",
    [
        ("https://twitter.com/jack/status/20", "20", "jack"),
        ("https://x.com/elonmusk/status/1234567890123456789", "1234567890123456789", "elonmusk"),
        ("https://x.com/home", None, None),
    ],
)
def test_x_tweet_id_and_url_detection(url, expected_id, expected_user):
    assert extract_tweet_id(url) == expected_id
    assert extract_username(url) == expected_user
    assert is_x_url(url) == (expected_id is not None)


def test_auto_route_chain():
    base_chain = ["manual", "whisper", "oembed", "youtube", "x_twitter"]
    
    # YouTube URL should put youtube first
    yt_routed = auto_route_chain(base_chain, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    assert yt_routed[0] == "youtube"
    assert len(yt_routed) == len(base_chain)
    assert set(yt_routed) == set(base_chain)

    # Shorts URL should also put youtube first
    shorts_routed = auto_route_chain(base_chain, "https://www.youtube.com/shorts/dQw4w9WgXcQ")
    assert shorts_routed[0] == "youtube"

    # X URL should put x_twitter first
    x_routed = auto_route_chain(base_chain, "https://x.com/jack/status/20")
    assert x_routed[0] == "x_twitter"
    assert len(x_routed) == len(base_chain)

    # Instagram URL should leave chain unchanged
    ig_routed = auto_route_chain(base_chain, "https://www.instagram.com/reel/C12345/")
    assert ig_routed == base_chain


def test_youtube_and_x_providers_always_available():
    yt = YouTubeProvider()
    assert yt.is_available() is True

    x = XTwitterProvider()
    assert x.is_available() is True

