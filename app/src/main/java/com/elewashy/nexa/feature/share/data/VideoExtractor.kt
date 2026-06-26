package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.feature.share.data.platform.FacebookVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.InstagramVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.PlatformVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ThreadsVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.TikTokVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.TwitterVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.YouTubeVideoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Routes shared URLs to the platform-specific extractor implementation.
 *
 * The public API and [ExtractionResult] type are intentionally preserved so
 * existing call sites do not need to know about the per-platform split.
 */
class VideoExtractor(
    youTubeExtractor: YouTubeExtractor = YouTubeExtractor()
) {

    data class ExtractionResult(
        val success: Boolean,
        val platform: String? = null,
        val videos: Map<String, String> = emptyMap(),
        val error: String? = null
    )

    private val youtube = YouTubeVideoExtractor(youTubeExtractor)
    private val facebook = FacebookVideoExtractor()
    private val instagram = InstagramVideoExtractor()
    private val threads = ThreadsVideoExtractor()
    private val tikTok = TikTokVideoExtractor()
    private val twitter = TwitterVideoExtractor()

    suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Extracting video from: $url")

        val extractor = extractorFor(url)
            ?: return@withContext ExtractionResult(
                success = false,
                error = "Unsupported platform"
            )

        extractor.extract(url)
    }

    private fun extractorFor(url: String): PlatformVideoExtractor? {
        return when (SharePlatformDetector.detect(url)) {
            SharePlatform.YOUTUBE -> youtube
            SharePlatform.FACEBOOK -> facebook
            SharePlatform.INSTAGRAM -> instagram
            SharePlatform.THREADS -> threads
            SharePlatform.TIKTOK -> tikTok
            SharePlatform.TWITTER -> twitter
            SharePlatform.VIDEO -> null
        }
    }

    private companion object {
        const val TAG = "VideoExtractor"
    }
}
