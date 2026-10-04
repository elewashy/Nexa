package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.TIKWM_BASE_URL
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.labelWithSize
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaImage
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import org.json.JSONObject
import java.net.URL

/**
 * Maps a TikWM API `data` payload to video, audio and photo-mode image
 * options. Pure, so the mapping is unit-testable without the network.
 */
internal object TikWmResponseParser {

    /** Maps TikWM's `data` object to the offered media. */
    fun toResult(data: JSONObject): ExtractionResult {
        // URL -> label, insertion-ordered; keyed by URL to drop duplicates.
        val options = linkedMapOf<String, String>()
        val images = photoModeImages(data)
        val audioUrl = resolveAgainstTikWm(data.optString("music"))

        QUALITY_CONFIGS.forEach { config ->
            val videoUrl = resolveAgainstTikWm(data.optString(config.urlKey)) ?: return@forEach
            // Photo posts may report their soundtrack as the "video".
            if (images.isNotEmpty() && videoUrl == audioUrl) return@forEach
            val label = labelWithSize(config.label, data.optLong(config.sizeKey).takeIf { it > 0L })
            options.putIfAbsent(videoUrl, if (config.watermarked) MediaLabel.watermarked(label) else label)
        }

        if (audioUrl != null) {
            options.putIfAbsent(audioUrl, MediaLabel.audio(labelWithSize("Audio", audioSize(data))))
        }

        if (options.isEmpty() && images.isEmpty()) {
            throw ExtractionException("No downloadable media found in TikWM response")
        }

        val videos = options.entries.associate { (mediaUrl, label) -> label to mediaUrl }
        return ExtractionResult.success("TikTok", videos, images)
    }

    /** Slides of a photo-mode post (`data.images`, an array of image URLs), in order. */
    private fun photoModeImages(data: JSONObject): List<MediaImage> {
        val urls = data.optJSONArray("images") ?: return emptyList()
        return (0 until urls.length())
            .mapNotNull { index -> resolveAgainstTikWm(urls.optString(index)) }
            .distinct()
            .map(::MediaImage)
    }

    private fun audioSize(data: JSONObject): Long? {
        return data.optLong("music_size").takeIf { it > 0L }
            ?: data.optLong("audio_size").takeIf { it > 0L }
            ?: data.optJSONObject("music_info")?.optLong("size")?.takeIf { it > 0L }
    }

    private fun resolveAgainstTikWm(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val decoded = ShareExtractionSupport.decodeUrl(path)
        if (!decoded.startsWith("/")) return decoded
        return try {
            URL(URL(TIKWM_BASE_URL), decoded).toString()
        } catch (_: Exception) {
            "$TIKWM_BASE_URL$decoded"
        }
    }

    private data class QualityConfig(
        val label: String,
        val urlKey: String,
        val sizeKey: String,
        val watermarked: Boolean
    )

    private val QUALITY_CONFIGS = listOf(
        QualityConfig("No Watermark", "play", "size", watermarked = false),
        QualityConfig("Watermarked", "wmplay", "wm_size", watermarked = true)
    )
}
