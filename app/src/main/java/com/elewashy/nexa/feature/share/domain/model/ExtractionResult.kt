package com.elewashy.nexa.feature.share.domain.model

/**
 * Result of extracting downloadable media from a shared URL.
 *
 * [videos] maps a display label to a download URL. Labels may carry
 * [MediaLabel] prefixes that the presentation layer decodes.
 */
data class ExtractionResult(
    val success: Boolean,
    val platform: String? = null,
    val videos: Map<String, String> = emptyMap(),
    val error: String? = null
) {
    companion object {
        fun success(platform: String, videos: Map<String, String>): ExtractionResult =
            ExtractionResult(success = true, platform = platform, videos = videos)

        fun failure(error: String): ExtractionResult =
            ExtractionResult(success = false, error = error)
    }
}
