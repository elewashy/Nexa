package com.elewashy.nexa.feature.share.data.platform

import org.json.JSONArray

/**
 * A single entry from a Meta-style `video_versions` array embedded in
 * Threads/Instagram post pages.
 */
internal data class VideoVersion(
    val url: String,
    val width: Int,
    val height: Int
)

/**
 * Parses the raw contents of a `video_versions` JSON array scraped from a
 * Threads or Instagram post page. The payload is JSON-escaped, so URLs are
 * unescaped before use.
 *
 * Returns an empty list when the payload is malformed instead of throwing:
 * callers typically have fallback strategies.
 */
internal fun parseVideoVersions(arrayBody: String): List<VideoVersion> {
    return try {
        val jsonArray = JSONArray("[${ShareExtractionSupport.decodeUrl(arrayBody)}]")
        buildList(jsonArray.length()) {
            for (i in 0 until jsonArray.length()) {
                val entry = jsonArray.getJSONObject(i)
                val url = entry.optString("url")
                if (url.isNotBlank()) {
                    add(
                        VideoVersion(
                            url = url,
                            width = entry.optInt("width"),
                            height = entry.optInt("height")
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
