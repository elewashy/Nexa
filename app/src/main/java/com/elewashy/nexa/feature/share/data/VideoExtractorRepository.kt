package com.elewashy.nexa.feature.share.data

/**
 * Facade around [VideoExtractor] and [YouTubeExtractor].
 *
 * [VideoExtractor] routes to per-platform extractors under `data/platform`.
 * The repository is the single @Singleton Hilt-injection point so that
 * [com.elewashy.nexa.feature.share.presentation.ShareVideoActivity] no
 * longer constructs extractors ad-hoc with `VideoExtractor()` /
 * `YouTubeExtractor()`.
 *
 * All suspend methods delegate directly to the underlying extractor. Re-
 * throwing is the extractor's concern; this interface intentionally does not
 * impose a [Result] wrapper — the pre-refactor call-site already handles
 * exceptions in its own try/catch blocks, and preserving that behaviour is
 * easier to verify than changing it.
 */
interface VideoExtractorRepository {
    /** Extracts video / audio qualities for an arbitrary shared URL. */
    suspend fun extract(url: String): VideoExtractor.ExtractionResult

    /** Fetches the file size (in bytes) for a single download [url] via HEAD. Returns null on failure. */
    suspend fun fetchFileSize(url: String, referer: String): Long?

    /**
     * Finalises a YouTube conversion request and returns the resulting
     * download URL. Only called for URLs prefixed with the CONVERT sentinel
     * (see `ShareVideoActivity.PREFIX_CONVERT`).
     */
    suspend fun convertYouTubeVideo(resourceContent: String): String
}
