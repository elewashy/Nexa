package com.elewashy.nexa.feature.share.domain.model

/**
 * A video or audio quality option presented to the user in the share sheet.
 */
data class VideoQuality(
    val quality: String,
    val url: String,
    val size: String? = null,
    val type: MediaType = MediaType.VIDEO,
    val hasWatermark: Boolean = false
) {

    enum class MediaType {
        VIDEO,
        AUDIO
    }

    data class DisplayLabels(
        val quality: String,
        val metadata: String? = null
    )

    /**
     * Splits the raw extractor label into a display quality and an optional
     * metadata line (file size), normalising resolutions like "576x1024" to
     * "576p".
     */
    fun getDisplayLabels(): DisplayLabels {
        var clean = quality
            .replace(NON_ASCII_REGEX, "")
            .replace(PREFIX_REGEX, "")
            .trim()

        val sizeText = size?.takeIf { it.isNotBlank() } ?: FILE_SIZE_REGEX.find(clean)
            ?.groupValues
            ?.get(1)
            ?.let { "$it MB" }

        if (sizeText != null) {
            clean = FILE_SIZE_REGEX.replace(clean, "")
                .replace(TRAILING_SEPARATOR_REGEX, "")
                .trim()
        }

        return DisplayLabels(
            quality = formatQualityLabel(clean),
            metadata = sizeText
        )
    }

    /**
     * Sort priority for ordering qualities. Higher is shown first.
     */
    fun getSortPriority(): Int {
        if (type == MediaType.AUDIO) return 0

        val qualityLower = quality.lowercase()
        return when {
            qualityLower.contains("8k") -> 1000
            qualityLower.contains("4k") || qualityLower.contains("2160") -> 900
            qualityLower.contains("1440") -> 800
            qualityLower.contains("1080") || qualityLower.contains("hd") -> 700
            qualityLower.contains("720") -> 600
            qualityLower.contains("480") -> 500
            qualityLower.contains("360") -> 400
            qualityLower.contains("240") -> 300
            RESOLUTION_FORMAT_REGEX.matches(qualityLower) ->
                RESOLUTION_HEIGHT_REGEX.find(qualityLower)?.groupValues?.get(1)?.toIntOrNull() ?: 100
            else -> 100
        }
    }

    private fun formatQualityLabel(rawQuality: String): String {
        val qualityText = rawQuality
            .replace(LEADING_SEPARATOR_REGEX, "")
            .replace(TRAILING_SEPARATOR_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        if (qualityText.isBlank()) {
            return when (type) {
                MediaType.AUDIO -> "Audio"
                MediaType.VIDEO -> "Video"
            }
        }

        RESOLUTION_REGEX.find(qualityText)?.let { match ->
            val width = match.groupValues[1].toIntOrNull() ?: 0
            val height = match.groupValues[2].toIntOrNull() ?: 0
            val qualityValue = minOf(width, height)
            if (qualityValue > 0) return "${qualityValue}p"
        }

        if (qualityText.matches(SIMPLE_P_REGEX)) {
            return qualityText.lowercase()
        }

        if (qualityText.matches(QUALITY_NUMBER_REGEX)) {
            val number = QUALITY_NUMBER_REGEX.find(qualityText)?.groupValues?.get(1).orEmpty()
            return "Quality $number"
        }

        return qualityText
            .replace(DASH_SEPARATOR_REGEX, " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private companion object {
        val NON_ASCII_REGEX = Regex("[^\\x00-\\x7F]+")
        val FILE_SIZE_REGEX = Regex("([\\d.]+)\\s*MB", RegexOption.IGNORE_CASE)
        val RESOLUTION_REGEX = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)")
        val SIMPLE_P_REGEX = Regex("\\d+p", RegexOption.IGNORE_CASE)
        val QUALITY_NUMBER_REGEX = Regex("Quality[_\\s]*(\\d+)", RegexOption.IGNORE_CASE)
        val DASH_SEPARATOR_REGEX = Regex("\\s*-\\s*")
        val MULTI_SPACE_REGEX = Regex("\\s+")
        val PREFIX_REGEX = Regex("^(AUDIO:|WATERMARK:)", RegexOption.IGNORE_CASE)
        val LEADING_SEPARATOR_REGEX = Regex("^[\\s:_-]+")
        val TRAILING_SEPARATOR_REGEX = Regex("[\\s:_-]+$")
        val RESOLUTION_FORMAT_REGEX = Regex(".*\\d+\\s*[x×]\\s*\\d+.*")
        val RESOLUTION_HEIGHT_REGEX = Regex("\\d+\\s*[x×]\\s*(\\d+)")
    }
}
