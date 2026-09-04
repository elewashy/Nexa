package com.elewashy.nexa.core.util

import androidx.core.net.toUri

/**
 * Human-readable host labels shared by every surface that shows a page URL
 * (tab cards, history rows, bookmarks, download sources, the omnibox).
 */
object UrlDisplay {

    /**
     * The host of [url] without a leading `www.`, or an empty string when the
     * URL has no parsable host (data:, javascript:, malformed input).
     */
    fun host(url: String): String {
        if (url.isBlank()) return ""
        return runCatching { url.toUri().host }
            .getOrNull()
            ?.removePrefix("www.")
            .orEmpty()
    }

    /** [host] with the raw [url] as the fallback so a label is never blank. */
    fun hostOrUrl(url: String): String = host(url).ifBlank { url }

    /**
     * Compact address-bar label: [host] followed by the path when it is more
     * than the bare root ("example.com/docs"). Falls back to the raw [url]
     * when no host can be parsed.
     */
    fun hostAndPath(url: String): String {
        if (url.isBlank()) return ""
        val uri = runCatching { url.toUri() }.getOrNull() ?: return url
        val host = uri.host?.removePrefix("www.") ?: return url
        val path = uri.encodedPath.orEmpty()
        return if (path.isEmpty() || path == "/") host else host + path
    }
}
