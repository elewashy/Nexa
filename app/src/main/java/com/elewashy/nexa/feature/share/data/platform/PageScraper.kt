package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaImage
import okio.BufferedSource

/**
 * Base for extractors that read a post page for embedded media.
 */
internal abstract class PageScraper(
    private val platformName: String,
    private val tag: String,
    protected val support: ShareExtractionSupport
) : PlatformVideoExtractor {

    /**
     * Fetches the page as a browser navigation and hands its body stream to
     * [read]. The response is closed when [read] returns, so a reader that
     * stops early also stops the download. Additional request headers can be
     * supplied as pairs.
     */
    protected suspend fun <T> readPage(
        url: String,
        vararg headers: Pair<String, String>,
        fetchDest: String = "document",
        read: (BufferedSource) -> T,
    ): T {
        val request = ShareExtractionSupport.browserRequest(url, fetchDest)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return support.executeOrThrow(request).use { response -> read(response.body.source()) }
    }

    protected fun success(videos: Map<String, String>, images: List<MediaImage> = emptyList()): ExtractionResult {
        Log.d(tag, "Found ${videos.size} video/audio options and ${images.size} images")
        return ExtractionResult.success(platformName, videos, images)
    }
}
