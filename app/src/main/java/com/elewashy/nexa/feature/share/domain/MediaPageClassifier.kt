package com.elewashy.nexa.feature.share.domain

import com.elewashy.nexa.feature.share.domain.model.MediaPage
import com.elewashy.nexa.feature.share.domain.model.MediaPresence
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import java.net.URI

/**
 * Classifies page URLs into [MediaPage]s using each platform's canonical
 * content URL shapes. Pure JVM code (no `android.net.Uri`) so it is cheap to
 * call on every URL change and unit-testable.
 */
object MediaPageClassifier {

    fun classify(url: String?): MediaPage? {
        val parsed = parse(url) ?: return null
        return when (SharePlatform.fromHost(parsed.host)) {
            SharePlatform.YOUTUBE -> youTube(parsed)
            SharePlatform.INSTAGRAM -> instagram(parsed)
            SharePlatform.TIKTOK -> tikTok(parsed)
            SharePlatform.TWITTER -> twitter(parsed)
            SharePlatform.THREADS -> threads(parsed)
            SharePlatform.FACEBOOK -> facebook(parsed)
            SharePlatform.VIDEO -> null
        }
    }

    private class ParsedUrl(val host: String, val segments: List<String>, val query: Map<String, String>) {
        fun segment(index: Int): String? = segments.getOrNull(index)

        /** The segment right after the first occurrence of one of [markers]. */
        fun after(vararg markers: String): String? {
            val index = segments.indexOfFirst { it in markers }
            return if (index >= 0) segment(index + 1) else null
        }
    }

    private fun parse(url: String?): ParsedUrl? {
        if (url.isNullOrBlank() || url.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trim('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name.isEmpty()) null else name to pair.substringAfter('=', "")
            }
            .toMap()
        return ParsedUrl(host, segments, query)
    }

    /** [key] is `id` or `kind:id`; the part after the last `:` is the content ID. */
    private fun guaranteed(platform: SharePlatform, key: String) =
        MediaPage(platform, key.substringAfterLast(':'), "${platform.id}:$key", MediaPresence.GUARANTEED)

    private fun needsCheck(platform: SharePlatform, key: String) =
        MediaPage(platform, key.substringAfterLast(':'), "${platform.id}:$key", MediaPresence.REQUIRES_PAGE_CHECK)

    // youtube.com/watch?v=ID, /shorts/ID, /live/ID, /embed/ID, youtu.be/ID
    private fun youTube(url: ParsedUrl): MediaPage? {
        val id = when {
            url.host == "youtu.be" || url.host.endsWith(".youtu.be") -> url.segment(0)
            url.segment(0) == "watch" -> url.query["v"]
            url.segment(0) in YOUTUBE_ID_SEGMENTS -> url.segment(1)
            else -> null
        }
        return id?.takeIf(YOUTUBE_ID::matches)?.let { guaranteed(SharePlatform.YOUTUBE, it) }
    }

    // instagram.com/{p|reel|reels|tv}/CODE, optionally prefixed by /{user};
    // instagram.com/share/{p|reel}/TOKEN app share links (redirects to a post).
    private fun instagram(url: ParsedUrl): MediaPage? {
        val isShareLink = url.segment(0) == "share"
        val markerIndex = url.segments.indexOfFirst { it in INSTAGRAM_MEDIA_SEGMENTS }
        if (markerIndex !in 0..1) return null
        val code = url.segment(markerIndex + 1)?.takeIf(MEDIA_CODE::matches) ?: return null
        return guaranteed(SharePlatform.INSTAGRAM, if (isShareLink) "share:$code" else code)
    }

    // tiktok.com/@user/{video|photo}/ID, tiktok.com/t/CODE, {vm|vt}.tiktok.com/CODE
    private fun tikTok(url: ParsedUrl): MediaPage? {
        val shortHost = url.host.startsWith("vm.") || url.host.startsWith("vt.")
        val key = when {
            shortHost -> url.segment(0)?.takeIf(MEDIA_CODE::matches)?.let { "short:$it" }
            url.segment(0) == "t" -> url.segment(1)?.takeIf(MEDIA_CODE::matches)?.let { "short:$it" }
            url.segment(0)?.startsWith("@") == true && url.segment(1) in TIKTOK_MEDIA_SEGMENTS ->
                url.segment(2)?.takeIf(NUMERIC_ID::matches)
            else -> null
        } ?: return null
        return guaranteed(SharePlatform.TIKTOK, key)
    }

    // x.com/{user|i|i/web}/status/ID[/photo/N|/video/N]
    private fun twitter(url: ParsedUrl): MediaPage? {
        val id = url.after("status", "statuses")?.takeIf(NUMERIC_ID::matches) ?: return null
        return needsCheck(SharePlatform.TWITTER, id)
    }

    // threads.{net|com}/@user/post/CODE
    private fun threads(url: ParsedUrl): MediaPage? {
        val code = url.after("post")?.takeIf(MEDIA_CODE::matches) ?: return null
        return needsCheck(SharePlatform.THREADS, code)
    }

    private fun facebook(url: ParsedUrl): MediaPage? {
        if (url.host == "fb.watch" || url.host.endsWith(".fb.watch")) {
            return url.segment(0)?.takeIf(MEDIA_CODE::matches)?.let { guaranteed(SharePlatform.FACEBOOK, "watch:$it") }
        }
        val first = url.segment(0)
        return when {
            // Videos and reels.
            first == "watch" -> url.query["v"]?.takeIf(NUMERIC_ID::matches)
                ?.let { guaranteed(SharePlatform.FACEBOOK, "video:$it") }
            first == "reel" -> url.segment(1)?.takeIf(NUMERIC_ID::matches)
                ?.let { guaranteed(SharePlatform.FACEBOOK, "video:$it") }
            first == "share" && url.segment(1) in FACEBOOK_SHARED_VIDEO -> url.segment(2)
                ?.takeIf(MEDIA_CODE::matches)?.let { guaranteed(SharePlatform.FACEBOOK, "share:$it") }
            "videos" in url.segments -> url.segments.lastOrNull()?.takeIf(NUMERIC_ID::matches)
                ?.let { guaranteed(SharePlatform.FACEBOOK, "video:$it") }
            // Photos.
            first == "photo" || first == "photo.php" -> url.query["fbid"]?.takeIf(NUMERIC_ID::matches)
                ?.let { guaranteed(SharePlatform.FACEBOOK, "photo:$it") }
            "photos" in url.segments -> url.segments.lastOrNull()?.takeIf(NUMERIC_ID::matches)
                ?.let { guaranteed(SharePlatform.FACEBOOK, "photo:$it") }
            // Posts: text-only posts are common, so the page must confirm media.
            first == "share" && url.segment(1) == "p" -> url.segment(2)?.takeIf(MEDIA_CODE::matches)
                ?.let { needsCheck(SharePlatform.FACEBOOK, "share:$it") }
            url.segment(1) == "posts" -> url.segment(2)?.takeIf(MEDIA_CODE::matches)
                ?.let { needsCheck(SharePlatform.FACEBOOK, "post:$it") }
            first == "permalink.php" || first == "story.php" -> url.query["story_fbid"]?.takeIf(MEDIA_CODE::matches)
                ?.let { needsCheck(SharePlatform.FACEBOOK, "post:$it") }
            else -> null
        }
    }

    private const val MAX_URL_LENGTH = 4096

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private val MEDIA_CODE = Regex("[A-Za-z0-9_-]+")
    private val NUMERIC_ID = Regex("\\d+")

    private val YOUTUBE_ID_SEGMENTS = setOf("shorts", "live", "embed", "v")
    private val INSTAGRAM_MEDIA_SEGMENTS = setOf("p", "reel", "reels", "tv")
    private val TIKTOK_MEDIA_SEGMENTS = setOf("video", "photo")
    private val FACEBOOK_SHARED_VIDEO = setOf("v", "r")
}
