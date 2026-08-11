package com.elewashy.nexa.feature.share.data.platform

import androidx.core.net.toUri
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import javax.inject.Inject

internal class ThreadsVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Threads", tag = "ThreadsVideoExtractor", support = support) {

    override val platform = SharePlatform.THREADS

    override fun extractFromPage(url: String): ExtractionResult {
        if (!POST_ID_RE.containsMatchIn(url)) {
            throw ExtractionException("Invalid Threads URL")
        }

        val authority = url.toUri().host?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTHORITY
        val html = fetchPage(url, "authority" to authority)

        val videos = linkedMapOf<String, String>()
        for (match in VIDEO_VERSIONS_RE.findAll(html)) {
            parseVideoVersions(match.groupValues[1]).forEach { version ->
                if (!videos.containsValue(version.url)) {
                    videos.putUniqueLabel(
                        ShareExtractionSupport.detectQuality(version.url, version.width, version.height),
                        version.url
                    )
                }
            }
            if (videos.isNotEmpty()) break
        }

        if (videos.isEmpty()) {
            throw ExtractionException("No video found in the post")
        }
        return success(videos)
    }

    /** Avoids label collisions when several versions map to the same quality name. */
    private fun MutableMap<String, String>.putUniqueLabel(label: String, url: String) {
        if (!containsKey(label)) {
            put(label, url)
            return
        }
        var index = 2
        var candidate = "$label ($index)"
        while (containsKey(candidate)) {
            index++
            candidate = "$label ($index)"
        }
        put(candidate, url)
    }

    private companion object {
        const val DEFAULT_AUTHORITY = "www.threads.com"
        val POST_ID_RE = Regex("/post/([^/?]+)")
        val VIDEO_VERSIONS_RE = Regex("\"video_versions\":\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
    }
}
