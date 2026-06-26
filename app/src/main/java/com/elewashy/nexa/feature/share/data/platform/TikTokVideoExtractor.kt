package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.TIKWM_BASE_URL
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.client
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.decodeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.util.Locale

internal class TikTokVideoExtractor : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing TikTok URL...")

        try {
            val legacyResult = extractLegacy(url)
            if (legacyResult.success) {
                Log.d(TAG, "Legacy TikTok API extraction succeeded with ${legacyResult.videos.size} media options")
                return@withContext legacyResult
            }

            Log.w(TAG, "Legacy TikTok API failed, falling back to TikWM: ${legacyResult.error}")
            val tikWmResult = extractWithTikWm(url)
            if (tikWmResult.success) return@withContext tikWmResult

            VideoExtractor.ExtractionResult(
                success = false,
                error = legacyResult.error ?: tikWmResult.error ?: "Failed to extract TikTok media"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting TikTok video", e)
            VideoExtractor.ExtractionResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private fun extractWithTikWm(url: String): VideoExtractor.ExtractionResult {
        val requestBody = FormBody.Builder()
            .add("url", url)
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url(TIKWM_API_URL)
            .post(requestBody)
            .header("User-Agent", USER_AGENT_DESKTOP)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", TIKWM_BASE_URL)
            .header("Referer", "$TIKWM_BASE_URL/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return VideoExtractor.ExtractionResult(
                    success = false,
                    error = "TikWM request failed: ${response.code}"
                )
            }

            val jsonResponse = response.body.string()
            if (jsonResponse.isBlank()) {
                return VideoExtractor.ExtractionResult(
                    success = false,
                    error = "TikWM returned an empty response"
                )
            }

            val apiJson = JSONObject(jsonResponse)
            if (apiJson.optInt("code", -1) != 0) {
                return VideoExtractor.ExtractionResult(
                    success = false,
                    error = apiJson.optString("msg").ifBlank { "TikWM API error" }
                )
            }

            val data = apiJson.optJSONObject("data")
                ?: return VideoExtractor.ExtractionResult(
                    success = false,
                    error = "TikWM response is missing data"
                )

            return mapTikWmResponse(data)
        }
    }

    private fun mapTikWmResponse(data: JSONObject): VideoExtractor.ExtractionResult {
        val media = linkedMapOf<String, TikTokMediaOption>()

        getTikWmVideoQualities(data).forEach { option ->
            media.putIfAbsent(option.url, option)
        }

        val audioUrl = fixTikTokUrl(data.optString("music"))
        if (audioUrl != null) {
            val duration = data.optInt("duration").takeIf { it > 0 }?.let { "${it}s" }
            media.putIfAbsent(
                audioUrl,
                TikTokMediaOption(
                    label = buildTikTokLabel(TikTokOptionType.AUDIO, "AUDIO", extra = duration),
                    url = audioUrl,
                    priority = 100
                )
            )
        }

        if (media.isEmpty()) {
            return VideoExtractor.ExtractionResult(
                success = false,
                error = "No downloadable media found in TikWM response"
            )
        }

        return VideoExtractor.ExtractionResult(
            success = true,
            platform = "TikTok",
            videos = media.values.sortedBy { it.priority }.associate { it.label to it.url }
        )
    }

    private fun getTikWmVideoQualities(data: JSONObject): List<TikTokMediaOption> {
        val qualityMap = listOf(
            TikTokQualityConfig(TikTokOptionType.VIDEO, "HD", "hdplay", "hd_size"),
            TikTokQualityConfig(TikTokOptionType.VIDEO, "SD", "play", "size"),
            TikTokQualityConfig(TikTokOptionType.WATERMARK, "Watermarked", "wmplay", "wm_size")
        )

        return qualityMap.mapIndexedNotNull { index, config ->
            val url = fixTikTokUrl(data.optString(config.urlKey)) ?: return@mapIndexedNotNull null
            val size = data.optLong(config.sizeKey).takeIf { it > 0L }
            TikTokMediaOption(
                label = buildTikTokLabel(config.type, config.label, size),
                url = url,
                priority = index
            )
        }
    }

    private fun extractLegacy(url: String): VideoExtractor.ExtractionResult {
        try {
            val videoId = when {
                url.contains("vt.tiktok.com") || url.contains("vm.tiktok.com") -> {
                    TIKTOK_SHORT_ID_RE.find(url)?.groupValues?.get(1)
                }
                else -> {
                    TIKTOK_NUMERIC_ID_RE.find(url)?.groupValues?.get(1)
                }
            }

            if (videoId == null) {
                return VideoExtractor.ExtractionResult(
                    success = false,
                    error = "Could not extract video ID from URL"
                )
            }

            Log.d(TAG, "Video ID: $videoId")

            val apiUrl = "$TIKTOK_API_URL?id=$videoId"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "*/*")
                .header("Origin", "https://tiktokdownloader.com")
                .header("Referer", "https://tiktokdownloader.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return VideoExtractor.ExtractionResult(
                        success = false,
                        error = "API request failed: ${response.code}"
                    )
                }

                val json = JSONObject(response.body.string())
                val videos = mutableMapOf<String, String>()

                if (json.has("video_no_watermark")) {
                    val mainVideo = json.getJSONObject("video_no_watermark")
                    val sizeMb = mainVideo.getDouble("size_mb")
                    val width = mainVideo.optInt("width", 720)
                    val height = mainVideo.optInt("height", 1280)
                    val quality = "${width}x${height}-${String.format(Locale.US, "%.1f", sizeMb)}MB"
                    videos[quality] = mainVideo.getString("url")
                }

                if (json.has("video_no_watermark_alternatives")) {
                    val alternatives = json.getJSONArray("video_no_watermark_alternatives")
                    for (i in 0 until alternatives.length()) {
                        val video = alternatives.getJSONObject(i)
                        val sizeMb = video.getDouble("size_mb")
                        val width = video.getInt("width")
                        val height = video.getInt("height")
                        val quality = "${width}x${height}-${String.format(Locale.US, "%.1f", sizeMb)}MB"
                        videos[quality] = video.getString("url")
                    }
                }

                if (json.has("video_watermark")) {
                    val watermarkVideo = json.getJSONObject("video_watermark")
                    val sizeMb = watermarkVideo.getDouble("size_mb")
                    val width = watermarkVideo.optInt("width", 720)
                    val height = watermarkVideo.optInt("height", 720)
                    val quality = "WATERMARK:${width}x${height}-${String.format(Locale.US, "%.1f", sizeMb)}MB"
                    videos[quality] = watermarkVideo.getString("url")
                }

                if (json.has("audio")) {
                    val audio = json.getJSONObject("audio")
                    if (audio.has("url")) {
                        val audioUrl = audio.getString("url")
                        val duration = if (audio.has("duration_seconds")) {
                            val durationSec = audio.getInt("duration_seconds")
                            val minutes = durationSec / 60
                            val seconds = durationSec % 60
                            if (minutes > 0) String.format(Locale.US, "%d:%02d", minutes, seconds) else "${seconds}s"
                        } else {
                            "Unknown"
                        }
                        videos["AUDIO:AUDIO - $duration"] = audioUrl
                        Log.d(TAG, "Found audio track: $duration")
                    }
                }

                Log.d(TAG, "Found ${videos.size} media options (video + audio)")

                if (videos.isEmpty()) {
                    return VideoExtractor.ExtractionResult(
                        success = false,
                        error = "No video found in API response"
                    )
                }

                return VideoExtractor.ExtractionResult(
                    success = true,
                    platform = "TikTok",
                    videos = videos
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting TikTok video using legacy API", e)
            return VideoExtractor.ExtractionResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private fun fixTikTokUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val decoded = decodeUrl(path)
        return if (decoded.startsWith("/")) {
            try {
                URL(URL(TIKWM_BASE_URL), decoded).toString()
            } catch (_: Exception) {
                "$TIKWM_BASE_URL$decoded"
            }
        } else {
            decoded
        }
    }

    private fun formatBytes(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val sizeMb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1fMB", sizeMb)
    }

    private fun buildTikTokLabel(
        type: TikTokOptionType,
        qualityName: String,
        sizeBytes: Long? = null,
        extra: String? = null
    ): String {
        val baseLabel = when (type) {
            TikTokOptionType.VIDEO -> qualityName
            TikTokOptionType.WATERMARK -> "WATERMARK:$qualityName"
            TikTokOptionType.AUDIO -> "AUDIO:$qualityName"
        }

        val parts = mutableListOf(baseLabel)
        formatBytes(sizeBytes)?.let(parts::add)
        extra?.takeIf { it.isNotBlank() }?.let(parts::add)
        return parts.joinToString(" - ")
    }

    private data class TikTokMediaOption(
        val label: String,
        val url: String,
        val priority: Int
    )

    private data class TikTokQualityConfig(
        val type: TikTokOptionType,
        val label: String,
        val urlKey: String,
        val sizeKey: String
    )

    private enum class TikTokOptionType {
        VIDEO,
        WATERMARK,
        AUDIO
    }

    private companion object {
        const val TAG = "TikTokVideoExtractor"
        const val TIKWM_API_URL = "https://www.tikwm.com/api/"
        const val TIKTOK_API_URL = "https://api.twitterpicker.com/tiktok/mediav2"
        val TIKTOK_SHORT_ID_RE = Regex("""/([\w]+)/?$""")
        val TIKTOK_NUMERIC_ID_RE = Regex("""/video/(\d+)""")
    }
}
