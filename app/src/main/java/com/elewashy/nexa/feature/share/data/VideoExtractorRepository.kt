package com.elewashy.nexa.feature.share.data

import com.elewashy.nexa.feature.share.domain.model.ExtractionResult

/**
 * Single entry point for the share feature into the extraction pipeline.
 */
interface VideoExtractorRepository {

    /** Extracts video / audio qualities for an arbitrary shared URL. */
    suspend fun extract(url: String): ExtractionResult

    /** Fetches the file size (in bytes) for a download [url] via HEAD. Returns null on failure. */
    suspend fun fetchFileSize(url: String, referer: String): Long?

    /**
     * Finalises a deferred YouTube conversion and returns the download URL.
     * Only called for options carrying the [com.elewashy.nexa.feature.share.domain.model.MediaLabel.CONVERT_PREFIX].
     */
    suspend fun convertYouTubeVideo(resourceContent: String): String
}
