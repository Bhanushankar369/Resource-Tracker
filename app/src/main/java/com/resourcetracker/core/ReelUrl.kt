package com.resourcetracker.core

import java.net.URI

/**
 * Handles extraction, normalisation, and platform detection for social URLs
 * (Instagram Reels/Posts, YouTube Videos/Shorts, X/Twitter Posts).
 */
object ReelUrl {

    enum class Platform(val label: String) {
        INSTAGRAM("Instagram"),
        YOUTUBE("YouTube"),
        X_TWITTER("X (Twitter)"),
        UNKNOWN("Web"),
    }

    /**
     * A link, stopping before any separator that belongs to the surrounding sentence.
     *
     * The last character class matters: share sheets append "· 4 likes" to the link,
     * and without excluding those the matched URL carries a trailing separator into
     * the card's reel_url, which breaks the duplicate key and the short label.
     */
    private val URL_PATTERN = Regex(
        """https?://[^\s"'<>\\]*[^\s"'<>\\·•|.,;:!?]""",
        RegexOption.IGNORE_CASE,
    )

    private val INSTAGRAM_HOSTS = setOf("instagram.com", "www.instagram.com")
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
    private val X_HOSTS = setOf("twitter.com", "www.twitter.com", "x.com", "www.x.com", "mobile.twitter.com")

    private val INSTAGRAM_PATH = Regex("""^/(reel|reels|p|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val YOUTUBE_SHORTS_PATH = Regex("""^/shorts/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val YOUTUBE_WATCH_PARAM = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
    private val X_STATUS_PATH = Regex("""^/([A-Za-z0-9_]+)/status/(\d+)""", RegexOption.IGNORE_CASE)

    /** App boilerplate appended to shared text; they are never part of a caption. */
    private val SHARE_NOISE = Regex(
        """\b(?:shared via instagram|view instagram posts|open instagram|""" +
            """see more on instagram|more on instagram|on instagram|""" +
            """shared via youtube|watch on youtube|watch on x|shared via x|shared via twitter)\b\.?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Trailing counters, e.g. the "· 1.2K likes · 12 comments" tail of a share.
     */
    private val TRAILING_STATS = Regex(
        """(?:\s*[·•|]\s*\d[\d,.]*\s*[KkMm]?\s*(?:likes?|comments?|views?|shares?|replies?))+\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** An @handle anywhere in the text. */
    private val HANDLE = Regex("""@([A-Za-z0-9._]{2,30})""")

    /** Pulls the first http(s) URL out of arbitrary shared text. */
    fun extract(sharedText: String?): String? =
        sharedText?.let { URL_PATTERN.find(it)?.value }

    /**
     * Detects which platform a URL belongs to.
     */
    fun detectPlatform(url: String): Platform {
        val normalized = normalize(url) ?: return Platform.UNKNOWN
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return Platform.UNKNOWN
        val host = uri.host?.lowercase().orEmpty()
        return when {
            host in INSTAGRAM_HOSTS -> Platform.INSTAGRAM
            host in YOUTUBE_HOSTS || host == "www.youtube.com" -> Platform.YOUTUBE
            host in X_HOSTS || host == "x.com" -> Platform.X_TWITTER
            else -> Platform.UNKNOWN
        }
    }

    /**
     * The caption a share sheet carried with the link, with the URL and
     * platform boilerplate removed.
     */
    fun captionFromSharedText(sharedText: String?): String {
        if (sharedText.isNullOrBlank()) return ""
        val url = extract(sharedText) ?: return ""
        var text = sharedText.replace(url, " ")
        var previous: String
        do {
            previous = text
            text = SHARE_NOISE.replace(text, " ")
            text = TRAILING_STATS.replace(text, " ")
            text = text.trimEnd(' ', '·', '|', '-', '–', '—', '.')
        } while (text != previous)

        return text.split(Regex("\\s+"))
            .joinToString(" ")
            .trim(' ', '·', '|', '-', '–', '—')
            .takeIf { it.length > 1 } ?: ""
    }

    /**
     * An @handle or creator username from shared text or URL.
     * For X/Twitter URLs, the account handle is in the path!
     */
    fun creatorFromSharedText(sharedText: String?): String? {
        if (sharedText == null) return null
        val url = extract(sharedText) ?: sharedText
        // If it's an X post URL, username is in the URL path: /username/status/id
        val xMatch = X_STATUS_PATH.find(runCatching { URI(url).path }.getOrDefault(""))
        if (xMatch != null) {
            val user = xMatch.groupValues[1]
            if (user.isNotBlank() && user.lowercase() != "i") return user
        }
        return HANDLE.find(sharedText)?.groupValues?.get(1)
    }

    /**
     * Canonicalises a URL so that tracking parameters, trailing slashes, and
     * redundant query strings collapse to the same key.
     * Supports Instagram, YouTube (watch and shorts), and X (Twitter).
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null

        // 1. YouTube handling
        if (host in YOUTUBE_HOSTS) {
            if (host == "youtu.be") {
                val videoId = uri.path.orEmpty().trim('/').takeIf { it.length == 11 }
                if (videoId != null) {
                    return "https://www.youtube.com/watch?v=$videoId"
                }
            } else {
                val path = uri.path.orEmpty()
                val shortsMatch = YOUTUBE_SHORTS_PATH.find(path)
                if (shortsMatch != null) {
                    val id = shortsMatch.groupValues[1]
                    return "https://www.youtube.com/shorts/$id"
                }
                val rawQuery = uri.rawQuery.orEmpty()
                val watchMatch = YOUTUBE_WATCH_PARAM.find("?$rawQuery")
                if (watchMatch != null) {
                    val id = watchMatch.groupValues[1]
                    return "https://www.youtube.com/watch?v=$id"
                }
            }
        }

        // 2. X / Twitter handling
        if (host in X_HOSTS) {
            val path = uri.path.orEmpty()
            val match = X_STATUS_PATH.find(path)
            if (match != null) {
                val username = match.groupValues[1]
                val statusId = match.groupValues[2]
                return "https://x.com/$username/status/$statusId"
            }
        }

        // 3. Instagram handling
        if (host in INSTAGRAM_HOSTS) {
            val path = uri.path.orEmpty()
            val match = INSTAGRAM_PATH.find(path)
            if (match != null) {
                val type = match.groupValues[1].lowercase()
                val id = match.groupValues[2]
                return "https://www.instagram.com/$type/$id"
            }
        }

        val cleanPath = uri.path.orEmpty().trimEnd('/')
        return "https://$host$cleanPath"
    }

    /** True for supported platforms (Instagram, YouTube, X). */
    fun isSupportedPlatform(raw: String): Boolean {
        return detectPlatform(raw) != Platform.UNKNOWN
    }

    /** True for Instagram reel/p/tv links, kept for backwards compatibility. */
    fun looksLikeInstagram(raw: String): Boolean {
        return detectPlatform(raw) == Platform.INSTAGRAM
    }
}
