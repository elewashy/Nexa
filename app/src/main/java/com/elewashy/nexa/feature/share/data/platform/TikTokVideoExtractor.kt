package com.elewashy.nexa.feature.share.data.platform

import android.util.Base64
import android.util.Log
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.TIKWM_BASE_URL
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.client
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.decodeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.util.Locale

internal class TikTokVideoExtractor : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        try {
            val tikWmResult = extractWithTikWm(url)
            if (tikWmResult.success) return@withContext tikWmResult

            VideoExtractor.ExtractionResult(
                success = false,
                error = tikWmResult.error ?: "Failed to extract TikTok media"
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

            return mapTikWmResponse(data, url)
        }
    }

    private fun mapTikWmResponse(data: JSONObject, sourceUrl: String): VideoExtractor.ExtractionResult {
        val media = linkedMapOf<String, TikTokMediaOption>()

        getTikWmVideoQualities(data).forEach { option ->
            media.putIfAbsent(option.url, option)
        }

        getSnapTikHdOption(sourceUrl)?.let { option -> media.putIfAbsent(option.url, option) }

        val audioUrl = fixTikTokUrl(data.optString("music"))
        if (audioUrl != null) {
            media.putIfAbsent(
                audioUrl,
                TikTokMediaOption(
                    label = buildTikTokLabel(
                        type = TikTokOptionType.AUDIO,
                        qualityName = "Audio",
                        sizeBytes = getTikWmAudioSize(data)
                    ),
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
        return TIKWM_VIDEO_QUALITIES.mapIndexedNotNull { index, config ->
            val url = fixTikTokUrl(data.optString(config.urlKey)) ?: return@mapIndexedNotNull null
            val size = data.optLong(config.sizeKey).takeIf { it > 0L }
            TikTokMediaOption(
                label = buildTikTokLabel(config.type, config.label, size),
                url = url,
                priority = index
            )
        }
    }

    private fun getTikWmAudioSize(data: JSONObject): Long? {
        return data.optLong("music_size").takeIf { it > 0L }
            ?: data.optLong("audio_size").takeIf { it > 0L }
            ?: data.optJSONObject("music_info")?.optLong("size")?.takeIf { it > 0L }
    }

    private fun getSnapTikHdOption(url: String): TikTokMediaOption? {
        return try {
            val hash = getSnapTikHash() ?: return null
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("url", url)
                .addFormDataPart("hash", hash)
                .build()
            val request = Request.Builder()
                .url(SNAPTIK_CHECK_URL)
                .post(requestBody)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Origin", SNAPTIK_BASE_URL)
                .header("Referer", SNAPTIK_HOME_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                parseSnapTikHdOption(response.body.string())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getSnapTikHash(): String? {
        val request = Request.Builder()
            .url(SNAPTIK_HOME_URL)
            .header("User-Agent", USER_AGENT_DESKTOP)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            return parseSnapTikHash(response.body.string())
        }
    }

    private fun parseSnapTikHash(html: String): String? {
        val shift = SNAPTIK_SHIFT_RE.find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (shift == null) return null

        val encodedArray = SNAPTIK_ARRAY_RE.find(html)?.groupValues?.get(1)
        if (encodedArray == null) return null

        return SNAPTIK_STRING_RE.findAll(encodedArray)
            .mapNotNull { match -> decryptSnapTikString(match.groupValues[1], shift) }
            .firstNotNullOfOrNull { decrypted ->
                SNAPTIK_HASH_RE.find(decrypted)?.groupValues?.get(1)
            }
    }

    private fun decryptSnapTikString(encoded: String, shift: Int): String? {
        return try {
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            buildString(decoded.length) {
                decoded.forEach { char ->
                    append(
                        if (char.isLetter()) {
                            val base = if (char.isUpperCase()) 'A'.code else 'a'.code
                            (((char.code - base - shift).floorMod(26)) + base).toChar()
                        } else {
                            char
                        }
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSnapTikHdOption(html: String): TikTokMediaOption? {
        return SNAPTIK_ANCHOR_RE.findAll(html).firstNotNullOfOrNull { match ->
            val attributes = match.groupValues[1]
            val className = SNAPTIK_CLASS_RE.find(attributes)?.groupValues?.get(1).orEmpty()
            if (!className.split(WHITESPACE_RE).contains("btn")) return@firstNotNullOfOrNull null

            val href = SNAPTIK_HREF_RE.find(attributes)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?: return@firstNotNullOfOrNull null
            val text = match.groupValues[2]
                .replace(HTML_TAG_RE, "")
                .replace(WHITESPACE_RE, " ")
                .trim()

            if (text != "Download") return@firstNotNullOfOrNull null

            TikTokMediaOption(
                label = "HD",
                url = resolveSnapTikUrl(href),
                priority = 10
            )
        }
    }

    private fun resolveSnapTikUrl(path: String): String = resolveUrl(path, SNAPTIK_BASE_URL).orEmpty()

    private fun fixTikTokUrl(path: String?): String? = resolveUrl(path, TIKWM_BASE_URL)

    private fun resolveUrl(path: String?, baseUrl: String): String? {
        if (path.isNullOrBlank()) return null
        val decoded = decodeUrl(path)
        return if (decoded.startsWith("/")) {
            try {
                URL(URL(baseUrl), decoded).toString()
            } catch (_: Exception) {
                "$baseUrl$decoded"
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
        const val SNAPTIK_BASE_URL = "https://snaptik.cx"
        const val SNAPTIK_HOME_URL = "$SNAPTIK_BASE_URL/en/"
        const val SNAPTIK_CHECK_URL = "$SNAPTIK_BASE_URL/en/check/"
        val TIKWM_VIDEO_QUALITIES = listOf(
            TikTokQualityConfig(TikTokOptionType.VIDEO, "No Watermark", "play", "size"),
            TikTokQualityConfig(TikTokOptionType.WATERMARK, "Watermarked", "wmplay", "wm_size")
        )
        val SNAPTIK_SHIFT_RE = Regex("""\(\s*_0x[a-f0-9]+\([^)]+\)\s*,\s*(\d+)\s*\)\)""")
        val SNAPTIK_ARRAY_RE = Regex("""var\s*\$?\s*=\s*\[(.*?)]\s*;""", RegexOption.DOT_MATCHES_ALL)
        val SNAPTIK_STRING_RE = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"""")
        val SNAPTIK_HASH_RE = Regex("""hash\s*:\s*'([^']+)'""")
        val SNAPTIK_ANCHOR_RE = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val SNAPTIK_CLASS_RE = Regex("""class=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val SNAPTIK_HREF_RE = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val HTML_TAG_RE = Regex("""<[^>]+>""")
        val WHITESPACE_RE = Regex("\\s+")
    }
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
