package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Probes a download URL to determine file size and whether the server
 * supports byte-range requests (segmented downloading).
 *
 * Strategy:
 *  1. Try a fast HEAD request first — trusts the `Accept-Ranges` header.
 *  2. If HEAD is blocked (some CDNs return 403/405 for HEAD), fall back
 *     to a GET with `Range: bytes=0-1` and read headers from the response.
 *
 * Range support is NOT verified with a second GET request here. Instead,
 * [SegmentDownloader] validates at download time: if it requests a range
 * but gets 200 instead of 206, it throws [RangeNotSupportedException],
 * and [DownloadTask.executeDownload] falls back to single-stream.
 * This avoids a wasteful verification request that broken servers
 * (which lie about Accept-Ranges) respond to with the full file body.
 *
 * This class is stateless — safe to call concurrently from multiple coroutines.
 */
object HttpProber {

    private const val TAG = "HttpProber"

    /**
     * Result of probing a download URL.
     *
     * @property contentLength   Total file size in bytes, or -1 if unknown.
     * @property supportsRanges  `true` if the server advertised `Accept-Ranges: bytes`.
     *                           May be a lie — actual support is validated at download time.
     * @property statusCode      HTTP status code from the successful probe.
     */
    data class ProbeResult(
        val contentLength: Long = -1L,
        val supportsRanges: Boolean = false,
        val statusCode: Int = 0
    )

    /**
     * Probes [url] using [client] and returns a [ProbeResult].
     *
     * @param client    Shared OkHttp client instance.
     * @param url       The download URL to probe.
     * @param headers   Extra headers (User-Agent, Referer, Cookie, etc.).
     */
    suspend fun probe(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): ProbeResult = withContext(Dispatchers.IO) {

        val baseBuilder = Request.Builder()
            .url(url)
            .apply {
                for ((key, value) in headers) {
                    addHeader(key, value)
                }
            }

        // ── Phase 1: HEAD ────────────────────────────────────────────────
        try {
            val headRequest = baseBuilder.build().newBuilder().head().build()
            val startMs = System.currentTimeMillis()

            client.newCall(headRequest).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startMs
                Log.d(TAG, "HEAD ${response.code} in ${elapsed}ms — $url")

                if (response.isSuccessful) {
                    // Return immediately on a successful HEAD — even if
                    // Content-Length is unknown. Falling through to a GET
                    // probe can consume one-time streaming responses
                    // (e.g. audio conversion APIs) and invalidate the URL.
                    return@withContext extractFromResponse(response)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed: ${e.message}")
        }

        // ── Phase 2: GET Range fallback ──────────────────────────────────
        try {
            val getRequest = baseBuilder.build().newBuilder()
                .get()
                .addHeader("Range", "bytes=0-1")
                .build()

            client.newCall(getRequest).execute().use { response ->
                Log.d(TAG, "GET-Range ${response.code} — $url")

                if (response.isSuccessful || response.code == 206) {
                    return@withContext extractFromRangeResponse(response)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET-Range probe failed: ${e.message}")
        }

        // ── Fallback: unknown size, no range support ─────────────────────
        Log.w(TAG, "Probe failed completely for $url, falling back to single-stream")
        ProbeResult()
    }

    /**
     * Extracts content-length and range support from a normal response (HEAD or 200 GET).
     */
    private fun extractFromResponse(response: okhttp3.Response): ProbeResult {
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val acceptRanges = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) ?: false

        Log.d(TAG, "Extracted: contentLength=$contentLength, acceptRanges=$acceptRanges")
        return ProbeResult(
            contentLength = contentLength,
            supportsRanges = acceptRanges,
            statusCode = response.code
        )
    }

    /**
     * Extracts total content-length from a 206 Partial Content response.
     * The Content-Range header format: `bytes 0-1/12345` where 12345 is the total.
     */
    private fun extractFromRangeResponse(response: okhttp3.Response): ProbeResult {
        val contentRange = response.header("Content-Range")
        var totalLength = -1L
        var supportsRanges = false

        if (contentRange != null) {
            // Format: "bytes 0-1/total"
            supportsRanges = true
            val totalPart = contentRange.substringAfter('/', "")
            if (totalPart.isNotEmpty() && totalPart != "*") {
                totalLength = totalPart.toLongOrNull() ?: -1L
            }
        }

        // Only trust Accept-Ranges header if the server actually returned 206.
        // A 200 response with Accept-Ranges: bytes means the server *claims*
        // range support but ignored our Range header — unreliable.
        if (!supportsRanges && response.code == 206) {
            supportsRanges = response.header("Accept-Ranges")
                ?.equals("bytes", ignoreCase = true) ?: false
        }

        if (totalLength <= 0) {
            totalLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
        }

        Log.d(TAG, "Range probe: totalLength=$totalLength, supportsRanges=$supportsRanges")
        return ProbeResult(
            contentLength = totalLength,
            supportsRanges = supportsRanges,
            statusCode = response.code
        )
    }
}
