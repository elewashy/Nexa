package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.TIKWM_BASE_URL
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.labelWithSize
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject

/**
 * TikTok extraction via the TikWM API, which resolves shared links (including
 * vm.tiktok.com short links) server-side and returns direct media URLs.
 */
internal class TikTokVideoExtractor @Inject constructor(
    private val support: ShareExtractionSupport
) : PlatformVideoExtractor {

    override val platform = SharePlatform.TIKTOK

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            buildResult(fetchTikWmData(url))
        } catch (e: ExtractionException) {
            ExtractionResult.failure(e.message ?: "Failed to extract TikTok media")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting TikTok video", e)
            ExtractionResult.failure("Error: ${e.message}")
        }
    }

    private fun fetchTikWmData(url: String): JSONObject {
        val requestBody = FormBody.Builder()
            .add("url", url)
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url(TIKWM_API_URL)
            .post(requestBody)
            .header("User-Agent", ShareExtractionSupport.USER_AGENT_DESKTOP)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", TIKWM_BASE_URL)
            .header("Referer", "$TIKWM_BASE_URL/")
            .build()

        support.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ExtractionException("TikWM request failed: ${response.code}")
            }

            val body = response.body.string()
            if (body.isBlank()) {
                throw ExtractionException("TikWM returned an empty response")
            }

            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                throw ExtractionException(json.optString("msg").ifBlank { "TikWM API error" })
            }

            return json.optJSONObject("data")
                ?: throw ExtractionException("TikWM response is missing data")
        }
    }

    private fun buildResult(data: JSONObject): ExtractionResult {
        // URL -> label, insertion-ordered; keyed by URL to drop duplicates.
        val options = linkedMapOf<String, String>()

        QUALITY_CONFIGS.forEach { config ->
            val videoUrl = resolveAgainstTikWm(data.optString(config.urlKey)) ?: return@forEach
            val label = labelWithSize(config.label, data.optLong(config.sizeKey).takeIf { it > 0L })
            options.putIfAbsent(videoUrl, if (config.watermarked) MediaLabel.watermarked(label) else label)
        }

        val audioUrl = resolveAgainstTikWm(data.optString("music"))
        if (audioUrl != null) {
            options.putIfAbsent(audioUrl, MediaLabel.audio(labelWithSize("Audio", audioSize(data))))
        }

        if (options.isEmpty()) {
            throw ExtractionException("No downloadable media found in TikWM response")
        }

        val videos = options.entries.associate { (mediaUrl, label) -> label to mediaUrl }
        return ExtractionResult.success("TikTok", videos)
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

    private companion object {
        const val TAG = "TikTokVideoExtractor"
        const val TIKWM_API_URL = "https://www.tikwm.com/api/"

        val QUALITY_CONFIGS = listOf(
            QualityConfig("No Watermark", "play", "size", watermarked = false),
            QualityConfig("Watermarked", "wmplay", "wm_size", watermarked = true)
        )
    }
}
