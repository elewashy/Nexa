package com.elewashy.nexa.feature.share.data.platform

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object ShareExtractionSupport {
    const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    const val TIKWM_BASE_URL = "https://www.tikwm.com"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val metadataClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val qualityPatterns = listOf(
        Regex("""_(\d+)p\.mp4"""),
        Regex("""\.(\d{3,4})\."""),
        Regex("""(\d{3,4})x(\d{3,4})""")
    )

    fun decodeUrl(url: String): String {
        return url
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\u002F", "/")
    }

    fun extractQuality(url: String): String? {
        for (pattern in qualityPatterns) {
            val match = pattern.find(url)
            if (match != null) {
                return when (match.groupValues.size) {
                    3 -> "${match.groupValues[1]}x${match.groupValues[2]}"
                    2 -> "${match.groupValues[1]}p"
                    else -> null
                }
            }
        }
        return null
    }

    fun detectQuality(
        url: String,
        width: Int? = null,
        height: Int? = null,
        context: String = ""
    ): String {
        val dimensionsQuality = qualityFromDimensions(width, height)
        if (dimensionsQuality != null) return dimensionsQuality

        val contextWidth = WIDTH_RE.find(context)?.groupValues?.get(1)?.toIntOrNull()
        val contextHeight = HEIGHT_RE.find(context)?.groupValues?.get(1)?.toIntOrNull()
        val contextQuality = qualityFromDimensions(contextWidth, contextHeight)
        if (contextQuality != null) return contextQuality

        val efgQuality = extractEfgQuality(url)
        if (efgQuality != null) return efgQuality

        return extractQuality(url)
            ?: when {
                url.contains("1280") -> "720p"
                url.contains("1920") -> "1080p"
                else -> "video"
            }
    }

    fun decodeBase64Url(downloadUrl: String, fileRegex: Regex, tag: String): String {
        try {
            val match = fileRegex.find(downloadUrl)
            if (match != null) {
                val encoded = URLDecoder.decode(match.groupValues[1], "UTF-8")
                val decoded = Base64.decode(encoded, Base64.DEFAULT)
                return String(decoded, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not decode URL: ${e.message}")
        }
        return downloadUrl
    }

    suspend fun withFileSizes(
        videos: Map<String, String>,
        referer: String,
        tag: String,
        maxRequests: Int = 4
    ): LinkedHashMap<String, String> = coroutineScope {
        val entries = videos.entries.toList()
        val sizesByUrl = entries
            .asSequence()
            .map { it.value }
            .distinct()
            .take(maxRequests)
            .map { url -> async(Dispatchers.IO) { url to fetchFileSize(url, referer, tag) } }
            .toList()
            .awaitAll()
            .toMap()

        entries.associateTo(LinkedHashMap()) { (label, url) ->
            labelWithSize(label, sizesByUrl[url]) to url
        }
    }

    fun labelWithSize(label: String, sizeBytes: Long?): String {
        if (sizeBytes == null || sizeBytes <= 0L || label.contains("MB", ignoreCase = true)) {
            return label
        }
        return "$label - ${formatBytes(sizeBytes)}"
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun qualityFromDimensions(width: Int?, height: Int?): String? {
        if (width == null || height == null) return null
        return when {
            width == 1280 || height == 1280 -> "720p"
            width == 1920 || height == 1920 -> "1080p"
            height > 0 -> "${height}p"
            width > 0 -> "${width}p"
            else -> null
        }
    }

    private fun extractEfgQuality(url: String): String? {
        return try {
            val efg = EFG_RE.find(url)?.groupValues?.get(1) ?: return null
            val decodedParam = URLDecoder.decode(efg.replace("%3D", "="), "UTF-8")
            val decoded = String(Base64.decode(decodedParam, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)

            when {
                decoded.contains("1280") -> "720p"
                decoded.contains("1920") -> "1080p"
                else -> RESOLUTION_RE.find(decoded)?.groupValues?.get(1)?.let { "${it}p" }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchFileSize(url: String, referer: String, tag: String): Long? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "*/*")
                .header("Referer", referer)
                .build()

            metadataClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not fetch file size: ${e.message}")
            null
        }
    }

    private val WIDTH_RE = Regex("width[\"\\\\]+:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val HEIGHT_RE = Regex("height[\"\\\\]+:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val EFG_RE = Regex("efg=([^&?]+)")
    private val RESOLUTION_RE = Regex("(\\d{3,4})")
}
