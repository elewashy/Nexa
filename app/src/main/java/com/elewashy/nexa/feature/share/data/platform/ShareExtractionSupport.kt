package com.elewashy.nexa.feature.share.data.platform

import android.util.Base64
import android.util.Log
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.core.network.awaitResponse
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared HTTP plumbing and parsing helpers for the platform extractors.
 *
 * Both clients are derived from [HttpClientProvider] so share-feature traffic
 * reuses the app-wide connection pool and dispatcher. Calls go through
 * [execute], which is cancellable: when the share sheet closes, its
 * in-flight requests are cancelled instead of running to their timeouts.
 */
@Singleton
internal class ShareExtractionSupport @Inject constructor(
    httpClientProvider: HttpClientProvider
) {

    /** Scraping client for post pages and extraction APIs. */
    private val client: OkHttpClient = httpClientProvider.newBuilder()
        // Fail fast on unreachable hosts so fallbacks get their turn.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Hard cap so slow-drip responses can't hang the quality sheet.
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Short-timeout client for best-effort metadata lookups (HEAD size checks). */
    private val metadataClient: OkHttpClient = httpClientProvider.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Executes [request], suspending without blocking a thread. The caller
     * owns the returned response and must close it (`use { }`); reading its
     * body blocks, so callers run on an IO dispatcher.
     */
    suspend fun execute(request: Request): Response = client.newCall(request).awaitResponse()

    /**
     * Executes [request] and returns its successful response, mapping HTTP
     * failures to [ExtractionException]s (404/410: the content is gone).
     */
    suspend fun executeOrThrow(request: Request): Response {
        val response = execute(request)
        if (response.isSuccessful) return response
        response.close()
        val reason = if (response.code == 404 || response.code == 410) ExtractionError.NO_MEDIA else ExtractionError.NETWORK
        throw ExtractionException("HTTP ${response.code} from ${request.url.host}", reason)
    }

    /** Fetches the content length of [url] via HEAD, or null on any failure. */
    suspend fun fetchFileSize(url: String, referer: String): Long? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "*/*")
                .header("Referer", referer)
                .build()

            metadataClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return null
                response.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch file size: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid media URL: ${e.message}")
            null
        }
    }

    companion object {

        const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val TIKWM_BASE_URL = "https://www.tikwm.com"

        private const val TAG = "ShareExtraction"
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 15L
        private const val CALL_TIMEOUT_SECONDS = 30L

        /**
         * A top-level navigation as a desktop browser makes it. Meta serves
         * logged-out post data only to requests that look like one, so all
         * scrapers share this header set. [fetchDest] is `iframe` for embeds.
         */
        fun browserRequest(url: String, fetchDest: String = "document"): Request.Builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", fetchDest)
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", if (fetchDest == "document") "none" else "cross-site")

        private val qualityPatterns = listOf(
            Regex("""_(\d+)p\.mp4"""),
            Regex("""\.(\d{3,4})\."""),
            Regex("""(\d{3,4})x(\d{3,4})""")
        )

        /** Unescapes JSON-encoded URLs found in scraped HTML/JSON payloads. */
        fun decodeUrl(url: String): String {
            return url
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u0025", "%")
                .replace("\\u002F", "/")
        }

        fun extractQuality(url: String): String? {
            for (pattern in qualityPatterns) {
                val match = pattern.find(url) ?: continue
                return when (match.groupValues.size) {
                    3 -> "${match.groupValues[1]}x${match.groupValues[2]}"
                    2 -> "${match.groupValues[1]}p"
                    else -> null
                }
            }
            return null
        }

        fun detectQuality(url: String, width: Int? = null, height: Int? = null): String {
            qualityFromDimensions(width, height)?.let { return it }
            extractEfgQuality(url)?.let { return it }
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

        /**
         * Quality options for a post's videos: the first video's renditions
         * keep their quality labels; later videos of a carousel are numbered
         * (`Video 2 (720p)`) so their options stay distinguishable. Labels
         * that would collide get a ` (n)` suffix rather than being dropped.
         */
        fun multiVideoOptions(videos: List<List<VideoVersion>>, qualityLabel: (VideoVersion) -> String): Map<String, String> {
            val options = linkedMapOf<String, String>()
            videos.forEachIndexed { index, versions ->
                versions.forEach { version ->
                    if (version.url in options.values) return@forEach
                    val quality = qualityLabel(version)
                    val label = if (index == 0) quality else "Video ${index + 1} (${heightLabel(quality)})"
                    options[uniqueLabel(options, label)] = version.url
                }
            }
            return options
        }

        /** `1080x1920` → `1080p`, so a numbered label is not re-parsed as a bare resolution. */
        private fun heightLabel(quality: String): String {
            val match = DIMENSIONS_RE.find(quality) ?: return quality
            return "${minOf(match.groupValues[1].toInt(), match.groupValues[2].toInt())}p"
        }

        private val DIMENSIONS_RE = Regex("""(\d+)\s*[xX]\s*(\d+)""")

        private fun uniqueLabel(options: Map<String, String>, label: String): String {
            if (label !in options) return label
            var n = 2
            while ("$label ($n)" in options) n++
            return "$label ($n)"
        }

        /** Appends a human-readable size to [label] when [sizeBytes] is known. */
        fun labelWithSize(label: String, sizeBytes: Long?): String {
            val sizeText = formatBytes(sizeBytes) ?: return label
            if (label.contains("MB", ignoreCase = true)) return label
            return "$label - $sizeText"
        }

        fun formatBytes(bytes: Long?): String? {
            if (bytes == null || bytes <= 0L) return null
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
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

        private val EFG_RE = Regex("efg=([^&?]+)")
        private val RESOLUTION_RE = Regex("(\\d{3,4})")

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
    }
}
