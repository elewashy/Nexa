package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage

/**
 * Open Graph metadata (`<meta property="og:…">`) of a page.
 *
 * [parse] reads the meta tags in one pass and stops at `</head>`, where they
 * end, so large pages are not rescanned for every property.
 */
internal class OpenGraph private constructor(private val properties: Map<String, String>) {

    /**
     * The page's `og:image` when it is served from one of [allowedHostSuffixes],
     * which filters out generic site logos shown on login walls and error pages.
     */
    fun image(allowedHostSuffixes: Set<String>): MediaImage? {
        val url = properties["og:image"]?.takeIf { it.startsWith("https://") } ?: return null
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
        if (allowedHostSuffixes.none { host == it || host.endsWith(".$it") }) return null
        return MediaImage(
            url = url,
            width = properties["og:image:width"]?.toIntOrNull() ?: 0,
            height = properties["og:image:height"]?.toIntOrNull() ?: 0,
        )
    }

    /** Whether the page declares a video (`og:video`). */
    val hasVideo: Boolean get() = "og:video" in properties || "og:video:url" in properties

    companion object {
        /** Open Graph properties of [html]; the first occurrence of each wins. */
        fun parse(html: String): OpenGraph {
            val headEnd = html.indexOf("</head>", ignoreCase = true).let { if (it < 0) html.length else it }
            val properties = HashMap<String, String>()
            for (tag in META_TAG_RE.findAll(html)) {
                if (tag.range.first > headEnd) break
                val attributes = tag.value
                val name = PROPERTY_RE.find(attributes)?.groupValues?.get(2)?.lowercase() ?: continue
                if (!name.startsWith("og:") || name in properties) continue
                CONTENT_RE.find(attributes)?.groupValues?.get(2)?.let(::decodeEntities)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { properties[name] = it }
            }
            return OpenGraph(properties)
        }

        private fun decodeEntities(value: String): String = value
            .replace("&amp;", "&")
            .replace("&#x2F;", "/")
            .replace("&#47;", "/")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        private val META_TAG_RE = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val PROPERTY_RE = Regex("""\b(?:property|name)\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        private val CONTENT_RE = Regex("""\bcontent\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
