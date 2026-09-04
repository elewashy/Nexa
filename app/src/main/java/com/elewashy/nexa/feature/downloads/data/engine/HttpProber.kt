package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import com.elewashy.nexa.feature.downloads.data.filename.FileNameResolver
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
     * @property statusCode      HTTP status code from the probe response; may be
     *                           an error code (4xx/5xx) when the server answered
     *                           but refused the request. 0 means no response at all.
     * @property contentType     MIME type reported by the server (without parameters), if any.
     * @property fileName        Filename from Content-Disposition or the final URL, if any.
     * @property finalUrl        URL after redirects, if the probe reached the server.
     */
    data class ProbeResult(
        val contentLength: Long = -1L,
        val supportsRanges: Boolean = false,
        val statusCode: Int = 0,
        val contentType: String? = null,
        val fileName: String? = null,
        val finalUrl: String? = null,
        /**
         * True when the server produced ANY HTTP response (even 4xx/5xx).
         * False means nothing was reached (timeout, DNS, connection failure) —
         * callers must treat that as "unknown", not as affirmative information
         * (e.g. resume must not discard progress based on it).
         */
        val reachable: Boolean = false
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

        // Last definitive HTTP ERROR response seen (4xx/5xx). Surfaced when no
        // phase succeeds so callers can distinguish "server said no" (dead URL)
        // from "nothing answered" (transient network problem).
        var lastErrorResponse: ProbeResult? = null

        // ── Phase 1: HEAD ────────────────────────────────────────────────
        try {
            val headRequest = baseBuilder.build().newBuilder().head().build()
            val startMs = System.currentTimeMillis()

            client.newCall(headRequest).awaitResponse().use { response ->
                val elapsed = System.currentTimeMillis() - startMs
                Log.d(TAG, "HEAD ${response.code} in ${elapsed}ms — $url")

                if (response.isSuccessful) {
                    // Return immediately on a successful HEAD — even if
                    // Content-Length is unknown. Falling through to a GET
                    // probe can consume one-time streaming responses
                    // (e.g. audio conversion APIs) and invalidate the URL.
                    return@withContext extractFromResponse(response).copy(reachable = true)
                }
                // Some CDNs reject HEAD outright (403/405) while serving GETs —
                // record the code but still fall through to the GET phase.
                lastErrorResponse = errorProbeResult(response)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed: ${e.message}")
        }

        // ── Phase 2: GET Range fallback ──────────────────────────────────
        try {
            val getRequest = baseBuilder.build().newBuilder()
                .get()
                .addHeader("Range", "bytes=0-1")
                .build()

            client.newCall(getRequest).awaitResponse().use { response ->
                Log.d(TAG, "GET-Range ${response.code} — $url")

                if (response.isSuccessful || response.code == 206) {
                    return@withContext extractFromRangeResponse(response).copy(reachable = true)
                }
                // Some WAFs/CDNs answer 4xx to HEAD *and* ranged GETs while
                // serving plain GETs — verify with a Range-less GET before
                // trusting the error code (otherwise a live URL could be
                // classified dead on resume).
                if (response.code in 400..499) {
                    val plainRequest = baseBuilder.build().newBuilder().get().build()
                    client.newCall(plainRequest).awaitResponse().use { plain ->
                        Log.d(TAG, "Plain GET ${plain.code} — $url")
                        if (plain.isSuccessful) {
                            return@withContext extractFromResponse(plain).copy(reachable = true)
                        }
                        lastErrorResponse = errorProbeResult(plain)
                    }
                } else {
                    // GET is the authoritative phase — its error code wins.
                    lastErrorResponse = errorProbeResult(response)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET-Range probe failed: ${e.message}")
        }

        // ── Fallback ─────────────────────────────────────────────────────
        lastErrorResponse?.let {
            Log.w(TAG, "Probe got HTTP ${it.statusCode} for $url — reporting as reachable error")
            return@withContext it
        }
        Log.w(TAG, "Probe failed completely for $url, falling back to single-stream")
        ProbeResult()
    }

    /** Minimal [ProbeResult] for an error response — status code + reachability. */
    private fun errorProbeResult(response: okhttp3.Response): ProbeResult = ProbeResult(
        statusCode = response.code,
        finalUrl = response.request.url.toString(),
        reachable = true
    )

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
            statusCode = response.code,
            contentType = extractContentType(response),
            fileName = extractFileName(response),
            finalUrl = response.request.url.toString()
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
            statusCode = response.code,
            contentType = extractContentType(response),
            fileName = extractFileName(response),
            finalUrl = response.request.url.toString()
        )
    }

    private fun extractContentType(response: okhttp3.Response): String? {
        return response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Filename candidate from Content-Disposition, falling back to the last
     * URL path segment when it looks like a file.
     */
    private fun extractFileName(response: okhttp3.Response): String? {
        response.header("Content-Disposition")?.let { cd ->
            FileNameResolver.extractFilenameFromContentDisposition(cd)?.let { return it }
        }

        val url = response.request.url.toString()
        val segment = url.substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
        return if (segment.isNotEmpty() && segment.contains('.')) segment else null
    }
}
