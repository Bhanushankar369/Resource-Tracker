"""Tests for the link fallback - the thing that makes the MVP work at all.

The provider chain cannot read an arbitrary Instagram reel (App Review), so these
cover the path that turns "we got a URL and nothing else" into a real card.
"""

from __future__ import annotations

from app.providers.link_card import build_link_result, clean_shared_caption

REEL = "https://www.instagram.com/reel/Dbg3dCckrpM/"


class TestCleanSharedCaption:
    def test_strips_the_url(self):
        caption = clean_shared_caption(
            f"Finally a good explanation {REEL}· Shared via Instagram"
        )
        assert REEL not in caption
        assert "Finally a good explanation" in caption

    def test_removes_instagram_boilerplate(self):
        for boilerplate in (
            "Shared via Instagram",
            "Shared via instagram",
            "on instagram",
            "View Instagram Posts",
            "See more on Instagram",
        ):
            assert boilerplate.lower() not in clean_shared_caption(
                f"My reel {REEL} {boilerplate}"
            ).lower()

    def test_removes_trailing_engagement_counters(self):
        assert clean_shared_caption(f"Cap text {REEL}· 1.2K likes · 84 comments") == (
            "Cap text"
        )

    def test_empty_when_only_a_link_was_shared(self):
        assert clean_shared_caption(REEL) == ""
        assert clean_shared_caption(None) == ""

    def test_collapses_whitespace(self):
        assert clean_shared_caption(f"A   reel\n{REEL}  caption") == "A reel caption"


class TestBuildLinkResult:
    def test_a_caption_alone_is_enough_to_build_a_card(self):
        result = build_link_result(REEL, description="Build habits slowly. #focus")

        assert result.has_content
        assert result.metadata["description"] == "Build habits slowly. #focus"
        assert result.hashtags == ["focus"]
        assert result.is_partial, "must be labelled partial so nothing is invented"

    def test_creator_lands_on_the_profile_and_the_author_metadata(self):
        result = build_link_result(REEL, description="Cap", creator="@Minimal.Design")

        assert result.creator is not None
        assert result.creator.username == "Minimal.Design"
        assert result.metadata["author"] == "Minimal.Design"

    def test_leading_at_sign_is_stripped(self):
        result = build_link_result(REEL, creator="@handle.name")
        assert result.creator is not None
        assert result.creator.username == "handle.name"

    def test_title_falls_back_to_the_creator_then_the_shortcode(self):
        by_creator = build_link_result(REEL, creator="@someone")
        assert by_creator.metadata["title"] == "@someone"

        by_shortcode = build_link_result(REEL)
        assert "Dbg3dCckrpM" in by_shortcode.metadata["title"]

    def test_a_caption_always_wins_over_the_handle_for_the_title(self):
        result = build_link_result(REEL, description="Learn to solder properly", creator="@x")
        assert result.metadata["title"] == "Learn to solder properly"

    def test_a_link_alone_is_still_enough_for_a_card(self):
        result = build_link_result(REEL)
        assert result.warnings
        # No caption and no creator, but the shortcode still names the reel, which is
        # better than the user getting an error screen.
        assert result.has_content
        assert result.metadata["shortcode"] == "Dbg3dCckrpM"

    def test_prior_warnings_are_kept_so_the_reason_survives(self):
        result = build_link_result(REEL, warnings=["oembed: HTTP 400 (code 10)"])
        assert any("code 10" in w for w in result.warnings)

    def test_hashtags_are_deduped_case_insensitively(self):
        result = build_link_result(REEL, description="#Focus and #focus and #Focus")
        assert result.hashtags == ["Focus"]

    def test_shortcode_is_exposed_as_metadata(self):
        result = build_link_result(REEL, description="Cap")
        assert result.metadata["shortcode"] == "Dbg3dCckrpM"
