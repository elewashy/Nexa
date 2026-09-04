package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.UnknownHostException
import java.net.SocketException
import kotlin.coroutines.coroutineContext

/**
 * Downloads a specific byte range of a file using HTTP Range requests
 * and writes the data directly to the correct file position.
 *
 * Each [SegmentDownloader] handles exactly one [DownloadSegment].
 * It supports:
 *  - Resume from the last written position (via [DownloadSegment.currentOffset])
 *  - Automatic retry with exponential backoff (max [MAX_RETRIES] attempts)
 *  - Immediate cancellation via coroutine cancellation + OkHttp Call.cancel()
 *  - **Network-aware failure**: DNS/connectivity errors are detected and surfaced
 *    as [NetworkLostException] so the task can auto-pause instead of exhausting retries.
 *  - Progress reporting via a callback
 *
 * Thread-safety: Each instance handles a single segment; the [SegmentFileWriter]
 * provides thread-safe writes at arbitrary positions.
 *
 * @property client       Shared OkHttp client.
 * @property url          Download URL.
 * @property headers      Extra HTTP headers (User-Agent, Referer, Cookie, etc.).
 * @property segment      The byte-range segment to download.
 * @property fileWriter   Thread-safe writer that handles RandomAccessFile I/O.
 * @property onProgress   Called after each buffer write with
 *                        (segmentId, bytesWrittenThisChunk).
 * @property isStale      Returns true once this downloader belongs to a
 *                        superseded task generation (fast pause→resume).
 *                        Stale coroutines must not write segment status:
 *                        their late PAUSED mark would overwrite the fresh
 *                        generation's state. The task's pause() marks
 *                        segments paused itself, so nothing is lost.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val segment: DownloadSegment,
    private val fileWriter: SegmentFileWriter,
    private val supportsRange: Boolean = true,
    private val bandwidthLimiter: BandwidthLimiter = BandwidthLimiter(),
    private val onProgress: (segmentId: Int, bytesWritten: Long) -> Unit = { _, _ -> },
    private val isStale: () -> Boolean = { false },
    /** Shared DownloadTask segment-state ownership boundary. */
    private val stateLock: Any = Any(),
) {
    companion object {
        private const val TAG = "SegmentDownloader"

        /** Maximum retries per segment before giving up. */
        const val MAX_RETRIES = 5

        /**
         * Buffer size for reading from the network stream.
         * 64 KB is optimal for mid-range (4GB RAM) devices; it balances 
         * throughput with lower GC pressure and per-segment memory overhead.
         */
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * Returns true if the exception indicates network connectivity loss
         * (DNS failure, connection reset, etc.) rather than a server-side error.
         */
        fun isNetworkError(e: Throwable): Boolean {
            val cause = e.cause ?: e
            return when (cause) {
                is UnknownHostException -> true  // DNS resolution failed
                is SocketException -> {
                    // "Software caused connection abort", "Connection reset", etc.
                    val msg = cause.message?.lowercase() ?: ""
                    msg.contains("connection abort") ||
                    msg.contains("connection reset") ||
                    msg.contains("network is unreachable") ||
                    msg.contains("broken pipe")
                }
                is java.net.ConnectException -> true  // Connection refused / unreachable
                else -> {
                    val msg = (cause.message ?: "").lowercase()
                    msg.contains("unable to resolve host") ||
                    msg.contains("no address associated with hostname") ||
                    msg.contains("network is unreachable")
                }
            }
        }
    }

    /**
     * The current active OkHttp [Call], if any.
     * Stored so that [cancelActiveCall] can interrupt blocking I/O immediately
     * when the coroutine is cancelled (e.g., pause/cancel).
     */
    @Volatile
    private var activeCall: Call? = null

    /**
     * Cancels the active OkHttp call, if any.
     * This will cause `stream.read()` to throw an IOException immediately,
     * making the coroutine respond to cancellation within milliseconds
     * instead of waiting for the full readTimeout.
     */
    fun cancelActiveCall() {
        activeCall?.cancel()
    }

    /**
     * Executes the segment download.
     *
     * Resumes from wherever [segment.downloadedBytes] left off.
     * On success, [segment.status] is set to [SegmentStatus.COMPLETED].
     * On failure (after all retries exhausted), it is set to [SegmentStatus.FAILED].
     *
     * @throws kotlinx.coroutines.CancellationException if the coroutine is cancelled.
     * @throws NetworkLostException if a network connectivity error is detected,
     *         allowing the [DownloadTask] to auto-pause rather than exhaust retries.
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        synchronized(stateLock) { segment.status = SegmentStatus.DOWNLOADING }

        while (synchronized(stateLock) {
            segment.retryCount <= MAX_RETRIES && segment.hasRemainingBytes
        }) {
            try {
                coroutineContext.ensureActive()

                downloadRange()

                val outcome = synchronized(stateLock) {
                    when {
                        !segment.hasRemainingBytes -> {
                            segment.status = SegmentStatus.COMPLETED
                            SegmentOutcome.COMPLETED
                        }
                        segment.endByte == Long.MAX_VALUE && segment.downloadedBytes > 0 -> {
                            segment.endByte = segment.startByte + segment.downloadedBytes - 1
                            segment.status = SegmentStatus.COMPLETED
                            SegmentOutcome.STREAM_COMPLETED
                        }
                        else -> SegmentOutcome.INCOMPLETE
                    }
                }
                if (outcome != SegmentOutcome.INCOMPLETE) {
                    Log.d(TAG, "Segment ${segment.id} completed (${segment.downloadedBytes / 1024}KB)")
                    return@withContext
                } else {
                    // downloadRange returned normally but segment isn't finished.
                    // This means the server terminated the connection early (EOF).
                    // We must increment retryCount to avoid infinite looping.
                    val retry = synchronized(stateLock) {
                        segment.retryCount++
                        if (segment.retryCount > MAX_RETRIES) segment.status = SegmentStatus.FAILED
                        segment.retryCount
                    }
                    Log.w(TAG, "Segment ${segment.id} premature EOF, retry $retry/$MAX_RETRIES")
                    if (retry > MAX_RETRIES) return@withContext

                    val delayMs = (1000L * (1 shl (retry - 1))).coerceAtMost(8000L)
                    kotlinx.coroutines.delay(delayMs)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cooperative cancellation — cancel the OkHttp call and re-throw.
                // A stale (superseded-generation) coroutine must not overwrite
                // the fresh generation's segment state; pause() marks segments
                // paused itself.
                cancelActiveCall()
                if (!isStale()) synchronized(stateLock) { segment.status = SegmentStatus.PAUSED }
                throw e
            } catch (e: NetworkLostException) {
                // Network is down — don't waste retries, surface immediately
                // so DownloadTask can auto-pause all segments.
                if (!isStale()) synchronized(stateLock) { segment.status = SegmentStatus.PAUSED }
                Log.w(TAG, "Segment ${segment.id}: network lost, pausing")
                throw e
            } catch (e: RangeNotSupportedException) {
                // Server doesn't support ranges — propagate immediately so
                // DownloadTask can fall back to single-stream download.
                synchronized(stateLock) { segment.status = SegmentStatus.FAILED }
                throw e
            } catch (e: Exception) {
                // Check if this was caused by OkHttp call cancellation
                if (activeCall?.isCanceled() == true) {
                    if (!isStale()) synchronized(stateLock) { segment.status = SegmentStatus.PAUSED }
                    throw kotlinx.coroutines.CancellationException(
                        "Segment ${segment.id} call cancelled"
                    )
                }

                // Check if this is a network connectivity error
                if (isNetworkError(e)) {
                    if (!isStale()) synchronized(stateLock) { segment.status = SegmentStatus.PAUSED }
                    Log.w(TAG, "Segment ${segment.id}: network error detected, pausing: ${e.message}")
                    throw NetworkLostException("Network lost during segment ${segment.id}", e)
                }

                val retry = synchronized(stateLock) {
                    segment.retryCount++
                    if (segment.retryCount > MAX_RETRIES) segment.status = SegmentStatus.FAILED
                    segment.retryCount
                }
                if (retry > MAX_RETRIES) {
                    Log.e(TAG, "Segment ${segment.id} failed after $MAX_RETRIES retries: ${e.message}")
                    return@withContext
                }

                val delayMs = (1000L * (1 shl (retry - 1))).coerceAtMost(8000L)
                Log.w(TAG, "Segment ${segment.id} retry $retry/$MAX_RETRIES " +
                        "in ${delayMs}ms: ${e.message}")
                kotlinx.coroutines.delay(delayMs)
            } finally {
                activeCall = null
            }
        }

        // If we exit the loop and segment isn't complete
        synchronized(stateLock) {
            if (segment.hasRemainingBytes && segment.status != SegmentStatus.COMPLETED) {
                segment.status = SegmentStatus.FAILED
            }
        }
    }

    /**
     * Executes a single HTTP request for the remaining bytes in this segment
     * and streams the response body into [fileWriter].
     */
    private suspend fun downloadRange() {
        // For non-range servers with existing progress, we need to skip bytes.
        // The server will send the full file from byte 0, but we already have
        // the first N bytes on disk. We skip those in the response stream and
        // only write new bytes, preserving progress across pause/resume.
        val range = synchronized(stateLock) {
            val skip = if (!supportsRange && segment.downloadedBytes > 0) segment.downloadedBytes else 0L
            RangeState(skip, if (skip > 0) segment.startByte else segment.currentOffset, segment.endByte)
        }
        val bytesToSkip = range.bytesToSkip
        val rangeStart = range.start
        val rangeEnd = range.end

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        // Apply custom headers
        for ((key, value) in headers) {
            requestBuilder.addHeader(key, value)
        }

        // Apply Range header if server supports it.
        // For bounded ranges, send bytes=start-end.
        // For unbounded ranges (unknown file size) where we have already downloaded some bytes, send bytes=start-
        if (supportsRange) {
            if (rangeEnd < Long.MAX_VALUE) {
                requestBuilder.addHeader("Range", "bytes=$rangeStart-$rangeEnd")
            } else if (rangeStart > 0) {
                requestBuilder.addHeader("Range", "bytes=$rangeStart-")
            }
        }

        val request = requestBuilder.build()
        val call = client.newCall(request)

        // Store the call so it can be cancelled from outside (pause/cancel)
        activeCall = call

        val response = call.execute()

        response.use { resp ->
            // CRITICAL: Validate response code.
            // If we requested a range (start > 0 or finite end), we MUST get 206 Partial Content.
            // If a server ignores the Range header and returns 200 OK (whole file),
            // writing it at the segment's offset would corrupt the file.
            val usesRange = supportsRange && (rangeStart > 0 || (rangeEnd < Long.MAX_VALUE && rangeEnd > 0))
            if (usesRange && resp.code != 206) {
                throw RangeNotSupportedException(
                    "Server ignored Range header (returned ${resp.code} instead of 206) " +
                            "for segment ${segment.id} at offset $rangeStart. " +
                            "Aborting to prevent file corruption."
                )
            }

            if (!resp.isSuccessful && resp.code != 206) {
                throw DownloadException(
                    "HTTP ${resp.code} for segment ${segment.id} [bytes=$rangeStart-$rangeEnd]"
                )
            }

            val body = resp.body

            val buffer = ByteArray(BUFFER_SIZE)
            val inputStream = body.byteStream()

            inputStream.use { stream ->
                // Skip bytes we already have on disk (non-range resume).
                // The server sends the full file; we discard the first N bytes
                // and only write the remaining portion we don't yet have.
                if (bytesToSkip > 0) {
                    Log.d(TAG, "Segment ${segment.id}: skipping $bytesToSkip bytes (already on disk)")
                    var skipped = 0L
                    while (skipped < bytesToSkip) {
                        coroutineContext.ensureActive()
                        // InputStream.skip() may skip fewer bytes than requested
                        val n = stream.skip((bytesToSkip - skipped).coerceAtMost(512 * 1024))
                        if (n <= 0) {
                            // skip() returned 0 or -1 — fall back to read()
                            val bytesRead = stream.read(buffer, 0,
                                buffer.size.toLong().coerceAtMost(bytesToSkip - skipped).toInt())
                            if (bytesRead == -1) {
                                Log.w(TAG, "Segment ${segment.id}: EOF while skipping at $skipped/$bytesToSkip")
                                return@downloadRange
                            }
                            skipped += bytesRead
                        } else {
                            skipped += n
                        }
                    }
                    Log.d(TAG, "Segment ${segment.id}: skip complete, resuming write at offset ${segment.currentOffset}")
                }

                while (true) {
                    // Check cancellation before each read
                    coroutineContext.ensureActive()

                    // Calculate how many bytes we still need
                    val remaining = synchronized(stateLock) {
                        if (segment.endByte < Long.MAX_VALUE) segment.remainingBytes else Long.MAX_VALUE
                    }

                    if (remaining <= 0) break

                    val toRead = buffer.size.toLong().coerceAtMost(remaining).toInt()
                    val bytesRead = stream.read(buffer, 0, toRead)
                    if (bytesRead == -1) break

                    // One shared limiter meters aggregate traffic across every file and segment.
                    // Delay happens outside the segment lock and before disk I/O.
                    bandwidthLimiter.throttle(bytesRead)

                    // Selection, write, and progress publication are one state
                    // operation with respect to dynamic splitting. Otherwise a
                    // split can create overlapping parent/child writes.
                    val written = synchronized(stateLock) {
                        val safeLength = if (segment.endByte == Long.MAX_VALUE) {
                            bytesRead
                        } else {
                            segment.remainingBytes.coerceAtMost(bytesRead.toLong()).toInt()
                        }
                        if (safeLength > 0) {
                            fileWriter.writeAt(segment.currentOffset, buffer, 0, safeLength)
                            segment.downloadedBytes += safeLength
                        }
                        safeLength
                    }

                    if (written > 0) onProgress(segment.id, written.toLong())
                }
            }
        }
    }

    private enum class SegmentOutcome { COMPLETED, STREAM_COMPLETED, INCOMPLETE }
    private data class RangeState(val bytesToSkip: Long, val start: Long, val end: Long)
}

/**
 * Custom exception for download-specific errors (non-retryable HTTP errors, etc.).
 */
class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a network connectivity error is detected (DNS failure, connection abort, etc.).
 * The [DownloadTask] catches this to auto-pause all segments rather than exhausting retries.
 */
class NetworkLostException(message: String, cause: Throwable? = null) : Exception(message, cause)
/**
 * Thrown when a server claims to support ranges but returns 200 OK for a range request.
 * Catching this allows the [DownloadTask] to fall back to single-stream download.
 */
class RangeNotSupportedException(message: String) : Exception(message)
