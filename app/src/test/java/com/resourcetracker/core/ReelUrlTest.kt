package com.resourcetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelUrlTest {

    @Test
    fun normalize_youtubeWatchUrl_preservesVideoIdAndStripsExtraParams() {
        val input = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share&t=42s"
        val normalized = ReelUrl.normalize(input)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", normalized)
    }

    @Test
    fun normalize_youtubeShortsUrl_normalizesCorrectly() {
        val input = "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share"
        val normalized = ReelUrl.normalize(input)
        assertEquals("https://www.youtube.com/shorts/dQw4w9WgXcQ", normalized)
    }

    @Test
    fun normalize_youtuBeUrl_normalizesToWatchUrl() {
        val input = "https://youtu.be/dQw4w9WgXcQ?si=abcdef123456"
        val normalized = ReelUrl.normalize(input)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", normalized)
    }

    @Test
    fun normalize_twitterUrl_normalizesToXDomain() {
        val input = "https://twitter.com/jack/status/20?s=20"
        val normalized = ReelUrl.normalize(input)
        assertEquals("https://x.com/jack/status/20", normalized)
    }

    @Test
    fun normalize_xUrl_preservesUsernameAndStatusId() {
        val input = "https://x.com/elonmusk/status/1234567890123456789?t=abc"
        val normalized = ReelUrl.normalize(input)
        assertEquals("https://x.com/elonmusk/status/1234567890123456789", normalized)
    }

    @Test
    fun normalize_instagramReelUrl_stripsTrackingParams() {
        val input = "https://www.instagram.com/reel/C0abcDEF_123/?igsh=MzRlODBiNWFlZA=="
        val normalized = ReelUrl.normalize(input)
        assertEquals("https://www.instagram.com/reel/C0abcDEF_123", normalized)
    }

    @Test
    fun detectPlatform_identifiesAllPlatforms() {
        assertEquals(
            ReelUrl.Platform.YOUTUBE,
            ReelUrl.detectPlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        assertEquals(
            ReelUrl.Platform.YOUTUBE,
            ReelUrl.detectPlatform("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
        assertEquals(
            ReelUrl.Platform.YOUTUBE,
            ReelUrl.detectPlatform("https://youtu.be/dQw4w9WgXcQ"),
        )
        assertEquals(
            ReelUrl.Platform.X_TWITTER,
            ReelUrl.detectPlatform("https://twitter.com/jack/status/20"),
        )
        assertEquals(
            ReelUrl.Platform.X_TWITTER,
            ReelUrl.detectPlatform("https://x.com/jack/status/20"),
        )
        assertEquals(
            ReelUrl.Platform.INSTAGRAM,
            ReelUrl.detectPlatform("https://www.instagram.com/reel/C0abcDEF_123/"),
        )
        assertEquals(
            ReelUrl.Platform.UNKNOWN,
            ReelUrl.detectPlatform("https://example.com/article/123"),
        )
    }

    @Test
    fun creatorFromSharedText_extractsHandleFromXUrl() {
        val xShare = "Check this out https://x.com/karpathy/status/9876543210"
        assertEquals("karpathy", ReelUrl.creatorFromSharedText(xShare))
    }

    @Test
    fun creatorFromSharedText_extractsHandleFromInstagramText() {
        val igShare = "Great tips from @hubermanlab https://instagram.com/reel/C0abcDEF_123/"
        assertEquals("hubermanlab", ReelUrl.creatorFromSharedText(igShare))
    }

    @Test
    fun captionFromSharedText_cleansNoiseAndStats() {
        val igShare = "Mindset matters https://www.instagram.com/reel/C0abcDEF_123/ · 4 likes · Shared via Instagram"
        assertEquals("Mindset matters", ReelUrl.captionFromSharedText(igShare))

        val ytShare = "Learn Kotlin Coroutines https://youtu.be/dQw4w9WgXcQ Shared via YouTube"
        assertEquals("Learn Kotlin Coroutines", ReelUrl.captionFromSharedText(ytShare))
    }

    @Test
    fun isSupportedPlatform_checksSupportedProviders() {
        assertTrue(ReelUrl.isSupportedPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(ReelUrl.isSupportedPlatform("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(ReelUrl.isSupportedPlatform("https://x.com/jack/status/20"))
        assertTrue(ReelUrl.isSupportedPlatform("https://www.instagram.com/reel/C0abcDEF_123/"))
        assertFalse(ReelUrl.isSupportedPlatform("https://example.com/random"))
    }
}
