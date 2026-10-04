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
 * Parses a Meta `video_versions` array (Threads/Instagram post data).
 * Entries that are not objects or have no URL are skipped; missing
 * dimensions are 0 (current payloads list renditions without them).
 */
internal fun parseVideoVersions(jsonArray: JSONArray): List<VideoVersion> = buildList(jsonArray.length()) {
    for (i in 0 until jsonArray.length()) {
        val entry = jsonArray.optJSONObject(i) ?: continue
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
