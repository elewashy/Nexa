package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.YouTubeExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.labelWithSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class YouTubeVideoExtractor(
    private val backend: YouTubeExtractor = YouTubeExtractor()
) : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing YouTube URL...")

        try {
            val result = backend.extract(url)
            if (!result.success) return@withContext failed(result.error ?: "Failed to extract YouTube video")

            val videos = result.videos.entries.associateTo(LinkedHashMap()) { (label, videoInfo) ->
                val url = if (videoInfo.isDirect) videoInfo.url else "$CONVERT_PREFIX${videoInfo.resourceContent}"
                labelWithSize(label, videoInfo.size) to url
            }

            Log.d(TAG, "Found ${videos.size} video/audio options (instant)")

            VideoExtractor.ExtractionResult(
                success = true,
                platform = PLATFORM,
                videos = videos
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube video", e)
            failed("Error: ${e.message}")
        }
    }

    private fun failed(message: String): VideoExtractor.ExtractionResult {
        return VideoExtractor.ExtractionResult(
            success = false,
            error = message
        )
    }

    private companion object {
        const val TAG = "YouTubeVideoExtractor"
        const val PLATFORM = "YouTube"
        const val CONVERT_PREFIX = "CONVERT:"
    }
}
