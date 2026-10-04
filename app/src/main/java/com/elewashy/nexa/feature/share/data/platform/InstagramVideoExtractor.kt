package com.elewashy.nexa.feature.share.data.platform

import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.MediaPageClassifier
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Instagram posts, reels and carousels, logged out.
 *
 * Sources, cheapest first, each streamed and abandoned as soon as the post
 * is read:
 *  1. The embed page (`/p/CODE/embed/captioned/`), Meta's public embedding
 *     surface (~250 KB read).
 *  2. The post page's structured data (~600 KB read). Instagram sometimes
 *     serves logged-out clients a shell without either, so both are tried.
 */
internal class InstagramVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Instagram", tag = TAG, support = support) {

    override val platform = SharePlatform.INSTAGRAM

    override suspend fun extract(url: String): ExtractionResult {
        val page = MediaPageClassifier.classify(url)?.takeIf { it.platform == SharePlatform.INSTAGRAM }
            ?: throw ExtractionException("Not an Instagram post URL", ExtractionError.UNSUPPORTED)
        // Unresolved /share/ links carry a share token, not the post's shortcode.
        if (page.contentKey.startsWith(SHARE_KEY_PREFIX)) {
            throw ExtractionException("Unresolved Instagram share link", ExtractionError.UNSUPPORTED)
        }
        val code = page.contentId

        val embed = readSource("embed") {
            readPage("$BASE_URL/p/$code/embed/captioned/", fetchDest = "iframe") { InstagramEmbedParser.scan(it, code) }
        }
        val post = choosePost(embed) {
            readSource("post page") { readPage("$BASE_URL/p/$code/") { MetaPostParser.scan(it, code) } }
        } ?: throw ExtractionException("Instagram returned no data for this post")

        val labels = HashMap<String, String>()
        val label = { version: VideoVersion -> labels.getOrPut(version.url) { qualityLabel(version) } }
        // Renditions of one video that map to the same quality add nothing: keep the first of each.
        val videos = ShareExtractionSupport.multiVideoOptions(post.videos.map { it.distinctBy(label) }, label)
            .toMutableMap()
        if (videos.isNotEmpty()) {
            // Audio-only variant via a third-party converter. The URL is
            // deterministic; validation is deferred to download time.
            val encodedVideoUrl = URLEncoder.encode(videos.values.first(), "UTF-8")
            videos[MediaLabel.audio("Audio")] = "$AUDIO_CONVERTER_API$encodedVideoUrl"
        }
        return success(videos, post.images)
    }

    /**
     * Reads one source; an HTTP error or a page without the post's data
     * yields null so the next source is tried. Network failures propagate:
     * both sources share a host, so retrying would only double the wait.
     */
    private suspend fun readSource(name: String, read: suspend () -> MetaPostMedia?): MetaPostMedia? = try {
        read()?.takeUnless { it.isEmpty }.also { if (it == null) Log.d(TAG, "No post data in $name") }
    } catch (e: ExtractionException) {
        Log.d(TAG, "$name unavailable: ${e.message}")
        null
    }

    /** The rendition's height (`720p`), read from the CDN's `efg` metadata, else from its declared size. */
    private fun qualityLabel(version: VideoVersion): String =
        efgResolution(version.url)
            ?: if (version.width > 0 && version.height > 0) "${version.width}x${version.height}" else "Video"

    /** Decodes the base64 `efg` query parameter to read the encoded resolution. */
    private fun efgResolution(videoUrl: String): String? {
        val efg = runCatching { videoUrl.toUri().getQueryParameter("efg") }.getOrNull() ?: return null
        return runCatching {
            val decodedParam = URLDecoder.decode(efg, "UTF-8")
            val metadata = String(Base64.decode(decodedParam, Base64.URL_SAFE), Charsets.UTF_8)
            EFG_RESOLUTION_RE.find(metadata)?.groupValues?.get(1)?.let { "${it}p" }
        }.getOrNull()
    }

    internal companion object {
        private const val TAG = "InstagramVideoExtractor"
        private const val BASE_URL = "https://www.instagram.com"
        private const val SHARE_KEY_PREFIX = "instagram:share:"
        private const val AUDIO_CONVERTER_API = "https://mp3.videodropper.app/api?url="

        /** `vencode_tag` such as `xpv_progressive.INSTAGRAM.CLIPS.C3.720.dash_baseline_1_v1`. */
        private val EFG_RESOLUTION_RE = Regex("""\.(\d{3,4})\.""")

        /**
         * Picks the post's media from the [embed] result and, only when the
         * embed had no data or hid a video ([MetaPostMedia.isComplete]), the
         * post page. The post page replaces the embed only when it is complete
         * or offers at least as much media. With an embed result in hand, a
         * network failure on the post page falls back to it; otherwise the
         * failure propagates.
         */
        internal suspend fun choosePost(
            embed: MetaPostMedia?,
            readPostPage: suspend () -> MetaPostMedia?,
        ): MetaPostMedia? {
            if (embed != null && embed.isComplete) return embed
            val page = try {
                readPostPage()
            } catch (e: IOException) {
                if (embed == null) throw e
                Log.d(TAG, "Post page unreachable, using the embed's media: ${e.message}")
                null
            }
            return when {
                page == null -> embed
                embed == null || page.isComplete || page.mediaCount >= embed.mediaCount -> page
                else -> embed
            }
        }
    }
}
