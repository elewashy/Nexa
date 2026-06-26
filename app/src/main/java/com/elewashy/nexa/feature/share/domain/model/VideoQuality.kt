package com.elewashy.nexa.feature.share.domain.model

import java.io.Serializable

/**
 * Represents a video or audio quality option for download
 */
data class VideoQuality(
    val quality: String,
    val url: String,
    val size: String? = null,
    val type: MediaType = MediaType.VIDEO,
    val hasWatermark: Boolean = false
) : Serializable {
    
    enum class MediaType {
        VIDEO,
        AUDIO
    }
    
    companion object {
        // Pre-compiled regex patterns for getDisplayName()
        private val NON_ASCII_REGEX = Regex("[^\\x00-\\x7F]+")
        private val FILE_SIZE_REGEX = Regex("([\\d.]+)\\s*MB", RegexOption.IGNORE_CASE)
        private val RESOLUTION_WITH_SIZE_REGEX = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)\\s*-?\\s*([\\d.]+)\\s*MB", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)")
        private val HD_WITH_NOTE_REGEX = Regex("(HD|SD|4K|8K)\\s*-\\s*([\\d.]+)\\s*MB\\s*\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
        private val QUALITY_WITH_SIZE_REGEX = Regex("(HD|SD|4K|8K)\\s*-\\s*([\\d.]+)\\s*MB", RegexOption.IGNORE_CASE)
        private val WATERMARK_REGEX = Regex("(Watermark(?:ed)?)\\s*-\\s*([\\d.]+)\\s*MB", RegexOption.IGNORE_CASE)
        private val SIMPLE_P_REGEX = Regex("\\d+p", RegexOption.IGNORE_CASE)
        private val QUALITY_NUMBER_REGEX = Regex("Quality[_\\s]*(\\d+)", RegexOption.IGNORE_CASE)
        private val DASH_SEPARATOR_REGEX = Regex("\\s*-\\s*")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val SPLIT_REGEX = Regex("[\\s-]+")
        private val PREFIX_REGEX = Regex("^(AUDIO:|WATERMARK:)", RegexOption.IGNORE_CASE)
        private val LEADING_SEPARATOR_REGEX = Regex("^[\\s:_-]+")
        private val TRAILING_SEPARATOR_REGEX = Regex("[\\s:_-]+$")
        
        // Pre-compiled regex patterns for getSortPriority()
        private val RESOLUTION_FORMAT_REGEX = Regex(".*\\d+\\s*[x×]\\s*\\d+.*")
        private val RESOLUTION_HEIGHT_REGEX = Regex("\\d+\\s*[x×]\\s*(\\d+)")
    }

    data class DisplayLabels(
        val quality: String,
        val metadata: String? = null
    )

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

        val qualityText = formatQualityLabel(clean)
        return DisplayLabels(
            quality = qualityText,
            metadata = sizeText
        )
    }
    
    /**
     * Get formatted display name with improved formatting
     * Converts resolutions to standard format (e.g., 576×1024 → 576p)
     */
    fun getDisplayName(): String {
        val labels = getDisplayLabels()
        if (labels.metadata != null) {
            return "${labels.quality}\n${labels.metadata}"
        }

        var displayName = quality.trim()
        
        // Remove any non-ASCII or garbled text first (like "الحصول ت")
        displayName = displayName.replace(NON_ASCII_REGEX, "").trim()
        
        // Step 1: Extract resolution and size if present (e.g., "576x1024-10.7MB" or "576 x 1024 - 10.7 MB")
        val resolutionWithSizeMatch = RESOLUTION_WITH_SIZE_REGEX.find(displayName)
        
        if (resolutionWithSizeMatch != null) {
            val width = resolutionWithSizeMatch.groupValues[1].toIntOrNull() ?: 0
            val height = resolutionWithSizeMatch.groupValues[2].toIntOrNull() ?: 0
            val size = resolutionWithSizeMatch.groupValues[3]
            
            // Use the smaller dimension as the quality (usually height for portrait, width for landscape)
            val qualityValue = minOf(width, height)
            return "${qualityValue}p\n${size} MB"
        }
        
        // Step 2: Handle simple resolution format without size (e.g., "576×1024" or "576 x 1024")
        val resolutionMatch = RESOLUTION_REGEX.find(displayName)
        
        if (resolutionMatch != null) {
            val width = resolutionMatch.groupValues[1].toIntOrNull() ?: 0
            val height = resolutionMatch.groupValues[2].toIntOrNull() ?: 0
            val qualityValue = minOf(width, height)
            return "${qualityValue}p"
        }
        
        // Step 3: Handle special formats like "HD - 8.8 MB (No Watermark)"
        val hdMatch = HD_WITH_NOTE_REGEX.find(displayName)
        
        if (hdMatch != null) {
            val quality = hdMatch.groupValues[1].uppercase()
            val size = hdMatch.groupValues[2]
            val note = hdMatch.groupValues[3].trim()
            
            // Simplify note
            val shortNote = when {
                note.contains("watermark", ignoreCase = true) -> ""
                else -> ""
            }
            
            return if (shortNote.isNotEmpty()) {
                "$quality\n$size MB\n$shortNote"
            } else {
                "$quality\n$size MB"
            }
        }
        
        // Step 4: Handle "HD/SD - X.X MB" format
        val qualityWithSizeMatch = QUALITY_WITH_SIZE_REGEX.find(displayName)
        
        if (qualityWithSizeMatch != null) {
            val quality = qualityWithSizeMatch.groupValues[1].uppercase()
            val size = qualityWithSizeMatch.groupValues[2]
            return "$quality\n$size MB"
        }
        
        // Step 5: Handle "Watermarked - X.X MB" format
        val watermarkMatch = WATERMARK_REGEX.find(displayName)
        
        if (watermarkMatch != null) {
            val size = watermarkMatch.groupValues[2]
            return "Watermark\n$size MB"
        }
        
        // Step 6: Already in "XXXp" format
        if (displayName.matches(SIMPLE_P_REGEX)) {
            return displayName.lowercase().replace("p", "p")
        }
        
        // Step 7: Handle "Quality_X" format
        if (displayName.matches(QUALITY_NUMBER_REGEX)) {
            val match = QUALITY_NUMBER_REGEX.find(displayName)
            return "Quality ${match?.groupValues?.get(1) ?: ""}"
        }
        
        // Step 8: Clean up any remaining format
        displayName = displayName
            .replace(DASH_SEPARATOR_REGEX, "\n")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        
        // If still too long, try to split
        if (displayName.length > 15 && !displayName.contains("\n")) {
            val parts = displayName.split(SPLIT_REGEX, limit = 2)
            if (parts.size == 2) {
                return "${parts[0]}\n${parts[1]}"
            }
        }
        
        return displayName
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
            val match = QUALITY_NUMBER_REGEX.find(qualityText)
            return "Quality ${match?.groupValues?.get(1) ?: ""}".trim()
        }

        return qualityText
            .replace(DASH_SEPARATOR_REGEX, " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    
    /**
     * Get sort priority for ordering qualities
     * Higher number = higher priority (shown first)
     */
    fun getSortPriority(): Int {
        val qualityLower = quality.lowercase()
        
        return when {
            // Audio gets lower priority
            type == MediaType.AUDIO -> 0
            
            // HD/4K/8K priorities
            qualityLower.contains("8k") -> 1000
            qualityLower.contains("4k") || qualityLower.contains("2160") -> 900
            qualityLower.contains("1440") -> 800
            qualityLower.contains("1080") || qualityLower.contains("hd") -> 700
            qualityLower.contains("720") -> 600
            qualityLower.contains("480") -> 500
            qualityLower.contains("360") -> 400
            qualityLower.contains("240") -> 300
            
            // Extract resolution from "WIDTHxHEIGHT" format
            qualityLower.matches(RESOLUTION_FORMAT_REGEX) -> {
                val height = RESOLUTION_HEIGHT_REGEX.find(qualityLower)?.groupValues?.get(1)?.toIntOrNull()
                height ?: 100
            }
            
            // Default
            else -> 100
        }
    }
}
