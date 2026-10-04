package com.elewashy.nexa.feature.share.domain.model

/**
 * Result of extracting downloadable media from a shared URL.
 *
 * [videos] maps a display label to a download URL. Labels may carry
 * [MediaLabel] prefixes that the presentation layer decodes.
 * [images] lists the post's images in display order. Failed results carry
 * a [failure] category for the UI and a diagnostic [error] for logs.
 */
data class ExtractionResult(
    val success: Boolean,
    val platform: String? = null,
    val videos: Map<String, String> = emptyMap(),
    val images: List<MediaImage> = emptyList(),
    val error: String? = null,
    val failure: ExtractionError? = null,
) {
    /** Whether there is anything to offer the user. */
    val hasMedia: Boolean get() = videos.isNotEmpty() || images.isNotEmpty()

    companion object {
        fun success(
            platform: String,
            videos: Map<String, String>,
            images: List<MediaImage> = emptyList(),
        ): ExtractionResult =
            ExtractionResult(success = true, platform = platform, videos = videos, images = images)

        fun failure(error: String, reason: ExtractionError = ExtractionError.NO_MEDIA): ExtractionResult =
            ExtractionResult(success = false, error = error, failure = reason)
    }
}
