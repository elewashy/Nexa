package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.data.YouTubeExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.labelWithSize
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Adapts [YouTubeExtractor] to the platform-extractor contract. Non-direct
 * formats carry a [MediaLabel.CONVERT_PREFIX] placeholder that the repository
 * resolves on demand when the user picks that quality.
 */
internal class YouTubeVideoExtractor @Inject constructor(
    private val backend: YouTubeExtractor
) : PlatformVideoExtractor {

    override val platform = SharePlatform.YOUTUBE

    override suspend fun extract(url: String): ExtractionResult {
        Log.d(TAG, "Processing YouTube URL...")

        return try {
            val result = backend.extract(url)
            if (!result.success) {
                return ExtractionResult.failure(result.error ?: "Failed to extract YouTube video")
            }

            val videos = result.videos.entries.associateTo(LinkedHashMap()) { (label, option) ->
                val target = if (option.isDirect) {
                    option.url
                } else {
                    MediaLabel.conversion(option.resourceContent.orEmpty())
                }
                labelWithSize(label, option.sizeBytes) to target
            }

            Log.d(TAG, "Found ${videos.size} video/audio options")
            ExtractionResult.success("YouTube", videos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube video", e)
            ExtractionResult.failure("Error: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "YouTubeVideoExtractor"
    }
}
