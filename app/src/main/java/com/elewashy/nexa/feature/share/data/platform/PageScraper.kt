package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Base for extractors that scrape a post page for embedded media URLs.
 *
 * Handles the boilerplate shared by all page-scraping platforms: running on
 * the IO dispatcher, fetching the page with browser-like headers, and
 * mapping failures to a user-presentable [ExtractionResult].
 */
internal abstract class PageScraper(
    private val platformName: String,
    private val tag: String,
    protected val support: ShareExtractionSupport
) : PlatformVideoExtractor {

    final override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(tag, "Processing $platformName URL...")
        try {
            extractFromPage(url)
        } catch (e: ExtractionException) {
            ExtractionResult.failure(e.message ?: "Extraction failed")
        } catch (e: Exception) {
            Log.e(tag, "Error extracting $platformName video", e)
            ExtractionResult.failure("Error: ${e.message}")
        }
    }

    /** Parses the post page at [url]. Throw [ExtractionException] for expected failures. */
    protected abstract fun extractFromPage(url: String): ExtractionResult

    /**
     * Fetches the page body, following redirects. Additional request headers
     * (e.g. a platform-specific `authority`) can be supplied as pairs.
     */
    protected fun fetchPage(url: String, vararg headers: Pair<String, String>): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ShareExtractionSupport.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        support.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ExtractionException("Failed to fetch page: ${response.code}")
            }
            return response.body.string()
        }
    }

    protected fun success(videos: Map<String, String>): ExtractionResult {
        Log.d(tag, "Found ${videos.size} media options")
        return ExtractionResult.success(platformName, videos)
    }
}
