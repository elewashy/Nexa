package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import javax.inject.Inject

internal class FacebookVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Facebook", tag = "FacebookVideoExtractor", support = support) {

    override val platform = SharePlatform.FACEBOOK

    override fun extractFromPage(url: String): ExtractionResult {
        val html = fetchPage(url)
        val videos = linkedMapOf<String, String>()

        HD_RE.find(html)?.let { videos.addVideo(it.groupValues[1], defaultLabel = "HD") }
        SD_RE.find(html)?.let { videos.addVideo(it.groupValues[1], defaultLabel = "SD") }
        BROWSER_NATIVE_RE.findAll(html).forEach { match ->
            val defaultLabel = if (match.value.contains("hd", ignoreCase = true)) "HD" else "SD"
            videos.addVideo(match.groupValues[1], defaultLabel)
        }

        if (videos.isEmpty()) {
            throw ExtractionException("No video found in the page")
        }
        return success(videos)
    }

    private fun MutableMap<String, String>.addVideo(encodedUrl: String, defaultLabel: String) {
        val videoUrl = ShareExtractionSupport.decodeUrl(encodedUrl)
        val label = ShareExtractionSupport.extractQuality(videoUrl) ?: defaultLabel
        putIfAbsent(label, videoUrl)
    }

    private companion object {
        val HD_RE = Regex(""""playable_url_quality_hd":"([^"]+)"""")
        val SD_RE = Regex(""""playable_url":"([^"]+)"""")
        val BROWSER_NATIVE_RE = Regex(""""browser_native_(?:hd|sd)_url":"([^"]+)"""")
    }
}
