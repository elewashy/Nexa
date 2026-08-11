package com.elewashy.nexa.feature.share.data.platform

import android.util.Base64
import androidx.core.net.toUri
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

internal class InstagramVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Instagram", tag = "InstagramVideoExtractor", support = support) {

    override val platform = SharePlatform.INSTAGRAM

    override fun extractFromPage(url: String): ExtractionResult {
        val shortcode = SHORTCODE_RE.find(url)?.groupValues?.get(2)
            ?: throw ExtractionException("Invalid Instagram URL")

        val pageUrl = if (url.endsWith('/')) url else "$url/"
        val html = fetchPage(pageUrl)

        val videos = linkedMapOf<String, String>()
        val seenUrls = mutableSetOf<String>()

        // Primary: the video_versions block belonging to this shortcode.
        shortcodeVersionsRegex(shortcode).find(html)?.let { match ->
            parseVideoVersions(match.groupValues[1]).forEach { version ->
                if (seenUrls.add(version.url)) {
                    videos[qualityLabel(version)] = version.url
                }
            }
        }

        // Fallback: first video_versions entry anywhere in the page.
        if (videos.isEmpty()) {
            FALLBACK_RE.find(html)?.let { match ->
                videos["${match.groupValues[2]}x${match.groupValues[3]}"] =
                    ShareExtractionSupport.decodeUrl(match.groupValues[1])
            }
        }

        if (videos.isEmpty()) {
            throw ExtractionException("No video found in the post")
        }

        // Audio-only variant via a third-party converter. The URL is
        // deterministic; validation is deferred to download time.
        val encodedVideoUrl = URLEncoder.encode(videos.values.first(), "UTF-8")
        videos[MediaLabel.audio("Audio")] = "$AUDIO_CONVERTER_API$encodedVideoUrl"

        return success(videos)
    }

    private fun shortcodeVersionsRegex(shortcode: String): Regex =
        Regex(""""code":"$shortcode".*?"video_versions":\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)

    private fun qualityLabel(version: VideoVersion): String = when {
        version.width > 0 && version.height > 0 -> "${version.width}x${version.height}"
        version.width > 0 -> "${version.width}p"
        else -> efgResolution(version.url) ?: "video"
    }

    /** Decodes the base64 `efg` query parameter to read the encoded resolution. */
    private fun efgResolution(videoUrl: String): String? {
        val efg = runCatching { videoUrl.toUri().getQueryParameter("efg") }.getOrNull() ?: return null
        return runCatching {
            val decodedParam = URLDecoder.decode(efg, "UTF-8")
            val metadata = String(Base64.decode(decodedParam, Base64.URL_SAFE), Charsets.UTF_8)
            EFG_RESOLUTION_RE.find(metadata)?.groupValues?.get(1)?.let { "${it}p" }
        }.getOrNull()
    }

    private companion object {
        const val AUDIO_CONVERTER_API = "https://mp3.videodropper.app/api?url="
        val SHORTCODE_RE = Regex("""/(p|reels?|tv)/([^/?]+)""")
        val EFG_RESOLUTION_RE = Regex("""\.(\d{3,4})\.""")
        val FALLBACK_RE = Regex(""""video_versions":\s*\[\s*\{[^\]]*?"url":"([^"]+)"[^\]]*?"width":(\d+)[^\]]*?"height":(\d+)""")
    }
}
