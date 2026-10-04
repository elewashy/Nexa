package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import javax.inject.Inject

internal class FacebookVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Facebook", tag = "FacebookVideoExtractor", support = support) {

    override val platform = SharePlatform.FACEBOOK

    override suspend fun extract(url: String): ExtractionResult {
        // Facebook's media keys can appear anywhere in the page, so it is read whole.
        val html = readPage(url) { it.readUtf8() }

        // One pass over the page for every video key; insertion order keeps HD before SD before native renditions.
        var hd: String? = null
        var sd: String? = null
        val native = ArrayList<MatchResult>()
        for (match in VIDEO_URL_RE.findAll(html)) {
            when (match.groupValues[1]) {
                KEY_HD -> if (hd == null) hd = match.groupValues[2]
                KEY_SD -> if (sd == null) sd = match.groupValues[2]
                else -> native += match
            }
        }
        val videos = linkedMapOf<String, String>()
        hd?.let { videos.addVideo(it, defaultLabel = "HD") }
        sd?.let { videos.addVideo(it, defaultLabel = "SD") }
        native.forEach { match -> videos.addVideo(match.groupValues[2], if (match.groupValues[1] == KEY_NATIVE_HD) "HD" else "SD") }

        if (videos.isNotEmpty()) return success(videos)

        // Photo pages and photo posts expose their image as og:image. Skipped
        // for video pages, where it would only be the video's thumbnail.
        val openGraph = OpenGraph.parse(html)
        val image = openGraph.image(CDN_HOSTS)?.takeUnless { openGraph.hasVideo }
            ?: throw ExtractionException("No media found in the page")
        return success(videos, listOf(image))
    }

    private fun MutableMap<String, String>.addVideo(encodedUrl: String, defaultLabel: String) {
        val videoUrl = ShareExtractionSupport.decodeUrl(encodedUrl)
        val label = ShareExtractionSupport.extractQuality(videoUrl) ?: defaultLabel
        putIfAbsent(label, videoUrl)
    }

    private companion object {
        /** Hosts serving Facebook media; anything else in og:image is a site logo. */
        val CDN_HOSTS = setOf("fbcdn.net")

        const val KEY_HD = "playable_url_quality_hd"
        const val KEY_SD = "playable_url"
        const val KEY_NATIVE_HD = "browser_native_hd_url"
        val VIDEO_URL_RE = Regex(""""(playable_url_quality_hd|playable_url|browser_native_hd_url|browser_native_sd_url)":"([^"]+)"""")
    }
}
