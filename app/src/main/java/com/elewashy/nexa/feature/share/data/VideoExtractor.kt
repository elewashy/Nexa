package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.feature.share.data.platform.PlatformVideoExtractor
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes shared URLs to the platform-specific extractor and is the single
 * error boundary of the extraction pipeline.
 *
 * Redirect-based share links (Threads/Instagram `/share/`, `t.co`, ...) are
 * unrolled first so extractors always see the canonical post URL. Extractors
 * throw on failure; this class maps exceptions to typed
 * [ExtractionResult] failures and is main-safe (all work runs on the
 * injected IO dispatcher).
 */
@Singleton
internal class VideoExtractor @Inject constructor(
    extractors: Set<@JvmSuppressWildcards PlatformVideoExtractor>,
    private val shareLinkResolver: ShareLinkResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val extractorsByPlatform = extractors.associateBy(PlatformVideoExtractor::platform)

    suspend fun extract(url: String): ExtractionResult = withContext(ioDispatcher) {
        val resolvedUrl = shareLinkResolver.resolve(url)
        val extractor = extractorsByPlatform[SharePlatformDetector.detect(resolvedUrl)]
            ?: return@withContext ExtractionResult.failure("Unsupported platform", ExtractionError.UNSUPPORTED)
        Log.d(TAG, "Extracting with ${extractor.platform.id}")
        try {
            extractor.extract(resolvedUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionException) {
            Log.w(TAG, "${extractor.platform.id}: ${e.message}")
            ExtractionResult.failure(e.message ?: "Extraction failed", e.reason)
        } catch (e: IOException) {
            Log.w(TAG, "${extractor.platform.id}: network failure: ${e.message}")
            ExtractionResult.failure(e.message ?: "Network error", ExtractionError.NETWORK)
        } catch (e: Exception) {
            // Malformed responses (unexpected JSON, schema changes) must never crash the share sheet.
            Log.e(TAG, "${extractor.platform.id}: unexpected failure", e)
            ExtractionResult.failure(e.message ?: "Extraction failed", ExtractionError.NO_MEDIA)
        }
    }

    private companion object {
        const val TAG = "VideoExtractor"
    }
}
