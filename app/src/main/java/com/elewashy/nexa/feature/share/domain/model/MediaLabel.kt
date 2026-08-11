package com.elewashy.nexa.feature.share.domain.model

/**
 * Codec for the label prefixes that travel through the quality map between
 * extractors and the presentation layer.
 */
object MediaLabel {
    const val AUDIO_PREFIX = "AUDIO:"
    const val WATERMARK_PREFIX = "WATERMARK:"
    const val CONVERT_PREFIX = "CONVERT:"

    enum class Kind { AUDIO, WATERMARKED_VIDEO, VIDEO, CONVERSION }

    fun audio(label: String) = "$AUDIO_PREFIX$label"
    fun watermarked(label: String) = "$WATERMARK_PREFIX$label"
    fun conversion(resourceContent: String) = "$CONVERT_PREFIX$resourceContent"

    fun parse(rawLabel: String): Pair<Kind, String> = when {
        rawLabel.startsWith(AUDIO_PREFIX) -> Kind.AUDIO to rawLabel.removePrefix(AUDIO_PREFIX)
        rawLabel.startsWith(WATERMARK_PREFIX) -> Kind.WATERMARKED_VIDEO to rawLabel.removePrefix(WATERMARK_PREFIX)
        rawLabel.startsWith(CONVERT_PREFIX) -> Kind.CONVERSION to rawLabel.removePrefix(CONVERT_PREFIX)
        else -> Kind.VIDEO to rawLabel
    }
}
