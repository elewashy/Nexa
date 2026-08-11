package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.feature.share.data.platform.PlatformVideoExtractor
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes shared URLs to the platform-specific extractor.
 *
 * Redirect-based share links (Threads `/share/`, `t.co`, ...) are unrolled
 * first so extractors always see the canonical post URL.
 */
@Singleton
internal class VideoExtractor @Inject constructor(
    extractors: Set<@JvmSuppressWildcards PlatformVideoExtractor>,
    private val shareLinkResolver: ShareLinkResolver
) {

    private val extractorsByPlatform = extractors.associateBy(PlatformVideoExtractor::platform)

    suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Extracting video from: $url")

        val resolvedUrl = shareLinkResolver.resolve(url)
        extractorsByPlatform[SharePlatformDetector.detect(resolvedUrl)]
            ?.extract(resolvedUrl)
            ?: ExtractionResult.failure("Unsupported platform")
    }

    private companion object {
        const val TAG = "VideoExtractor"
    }
}
