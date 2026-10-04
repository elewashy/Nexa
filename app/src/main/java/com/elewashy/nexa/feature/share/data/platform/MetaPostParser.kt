package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Media of one Instagram / Threads post.
 *
 * @property videos every video (or video slide) in order, each with its renditions.
 * @property images best-resolution image of every photo (or photo slide), in order.
 * @property isComplete false when a video slide was listed without a playable
 *   URL, so another source may still provide it.
 */
internal data class MetaPostMedia(
    val videos: List<List<VideoVersion>>,
    val images: List<MediaImage>,
    val isComplete: Boolean = true,
) {
    val isEmpty: Boolean get() = videos.isEmpty() && images.isEmpty()

    /** Number of videos and images offered. */
    val mediaCount: Int get() = videos.size + images.size
}

/**
 * Reads a post's media from the structured data Meta embeds in Instagram and
 * Threads post pages (`<script type="application/json">` blocks).
 *
 * Both apps share Meta's media schema: an item with `code` (the shortcode in
 * the URL), `media_type` (1 photo, 2 video, 8 carousel, 19 text), and
 * `image_versions2` / `video_versions` / `carousel_media`. Matching the item
 * by its `code` guarantees the media belongs to the shared post rather than
 * to a related post rendered on the same page.
 */
internal object MetaPostParser {

    /**
     * Streams [source] (a post page) until the post [code] is found. Returns
     * null when the page carries no data for it (login wall, removed post).
     * A post that is found but has no media (a text post) yields an empty
     * [MetaPostMedia].
     */
    fun scan(source: BufferedSource, code: String): MetaPostMedia? {
        if (code.isBlank()) return null
        return HtmlStreamScanner.firstJsonScript(source, needle = "\"code\":\"$code\"") { body -> parseBlock(body, code) }
    }

    /** Parses one JSON script body; null when it does not hold the post's media item. */
    fun parseBlock(body: String, code: String): MetaPostMedia? {
        // Cheap pre-check: blocks that only reference the code (routing, timelines) are skipped unparsed.
        if (MEDIA_KEYS.none(body::contains)) return null
        val root = try {
            parseRoot(body)
        } catch (_: JSONException) {
            return null
        } ?: return null
        return findPost(root, code)?.let(::mediaOf)
    }

    private fun parseRoot(body: String): Any? {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> null
        }
    }

    /** Iterative depth-first search, so deeply nested page data cannot overflow the stack. */
    private fun findPost(root: Any, code: String): JSONObject? {
        val stack = ArrayDeque<Any>().apply { addLast(root) }
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is JSONObject -> {
                    if (node.optString("code") == code && node.isMediaItem()) return node
                    val keys = node.keys()
                    while (keys.hasNext()) node.opt(keys.next())?.let { if (it.isContainer()) stack.addLast(it) }
                }
                is JSONArray -> for (i in node.length() - 1 downTo 0) {
                    node.opt(i)?.let { if (it.isContainer()) stack.addLast(it) }
                }
            }
        }
        return null
    }

    private fun Any.isContainer() = this is JSONObject || this is JSONArray

    private fun JSONObject.isMediaItem() = MEDIA_ITEM_KEYS.any(::has)

    private fun mediaOf(post: JSONObject): MetaPostMedia {
        val items = post.optJSONArray("carousel_media")
            ?.let { slides -> (0 until slides.length()).mapNotNull(slides::optJSONObject) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(post)

        val videos = ArrayList<List<VideoVersion>>()
        val images = ArrayList<MediaImage>(items.size)
        var isComplete = true
        for (item in items) {
            if (item.isVideo()) {
                val versions = item.optJSONArray("video_versions")?.let(::parseVideoVersions).orEmpty().distinctBy { it.url }
                if (versions.isEmpty()) isComplete = false else videos += versions
            } else {
                // A video's image_versions2 is only its cover, so photos are read from photo items only.
                bestImage(item)?.takeIf { image -> images.none { it.url == image.url } }?.let(images::add)
            }
        }
        return MetaPostMedia(videos, images, isComplete)
    }

    private fun JSONObject.isVideo(): Boolean =
        optInt("media_type") == MEDIA_TYPE_VIDEO || (optJSONArray("video_versions")?.length() ?: 0) > 0

    /**
     * The largest candidate. Candidates are listed largest first and may
     * omit their size, in which case the item's original size applies.
     */
    private fun bestImage(item: JSONObject): MediaImage? {
        val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return null
        var best: JSONObject? = null
        var bestPixels = -1L
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            if (!candidate.optString("url").startsWith("https://")) continue
            val pixels = candidate.optLong("width") * candidate.optLong("height")
            if (pixels > bestPixels) {
                best = candidate
                bestPixels = pixels
            }
        }
        best ?: return null
        val sized = bestPixels > 0
        return MediaImage(
            url = best.getString("url"),
            width = if (sized) best.optInt("width") else item.optInt("original_width"),
            height = if (sized) best.optInt("height") else item.optInt("original_height"),
        )
    }

    private const val MEDIA_TYPE_VIDEO = 2
    private val MEDIA_ITEM_KEYS = listOf("image_versions2", "video_versions", "carousel_media")
    private val MEDIA_KEYS = MEDIA_ITEM_KEYS.map { "\"$it\"" }
}

/**
 * Reads a post from Instagram's embed page (`/p/CODE/embed/captioned/`),
 * the surface Meta serves for embedding public posts on other sites. Its
 * data is a JSON document in a `contextJSON` string (legacy GraphQL schema:
 * `shortcode_media` with `display_resources`, `video_url` and
 * `edge_sidecar_to_children`).
 */
internal object InstagramEmbedParser {

    fun scan(source: BufferedSource, code: String): MetaPostMedia? =
        HtmlStreamScanner.jsonStringAfter(source, marker = "\"contextJSON\":")?.let { parse(it, code) }

    fun parse(contextJson: String, code: String): MetaPostMedia? {
        val media = try {
            JSONObject(contextJson).optJSONObject("gql_data")?.optJSONObject("shortcode_media")
        } catch (_: JSONException) {
            null
        } ?: return null
        if (media.optString("shortcode") != code) return null

        val nodes = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            ?.let { edges -> (0 until edges.length()).mapNotNull { edges.optJSONObject(it)?.optJSONObject("node") } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(media)

        val videos = ArrayList<List<VideoVersion>>()
        val images = ArrayList<MediaImage>(nodes.size)
        var isComplete = true
        for (node in nodes) {
            if (node.optBoolean("is_video")) {
                val url = node.optString("video_url")
                if (url.startsWith("https://")) {
                    val size = node.optJSONObject("dimensions")
                    videos += listOf(VideoVersion(url, size?.optInt("width") ?: 0, size?.optInt("height") ?: 0))
                } else {
                    // The embed hides some videos (e.g. licensed audio); the post page may still list them.
                    isComplete = false
                }
            } else {
                bestResource(node)?.takeIf { image -> images.none { it.url == image.url } }?.let(images::add)
            }
        }
        return MetaPostMedia(videos, images, isComplete)
    }

    /** Largest `display_resources` entry; the list mixes full-aspect and square-cropped renditions. */
    private fun bestResource(node: JSONObject): MediaImage? {
        val resources = node.optJSONArray("display_resources") ?: return null
        var best: MediaImage? = null
        for (i in 0 until resources.length()) {
            val resource = resources.optJSONObject(i) ?: continue
            val url = resource.optString("src").takeIf { it.startsWith("https://") } ?: continue
            val image = MediaImage(url, resource.optInt("config_width"), resource.optInt("config_height"))
            if (best == null || image.width.toLong() * image.height > best.width.toLong() * best.height) best = image
        }
        return best
    }
}
