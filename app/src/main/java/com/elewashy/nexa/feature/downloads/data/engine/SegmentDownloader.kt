package com.elewashy.nexa.feature.downloads.data.engine

import com.elewashy.nexa.core.network.awaitResponse
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.EOFException
import kotlin.coroutines.coroutineContext

/** One fixed, disjoint range; progress is published only after a complete disk write. */
internal class SegmentDownloader(
    private val client: OkHttpClient,
    private val item: DownloadItem,
    private val segment: DownloadSegment,
    private val writer: SegmentFileWriter,
    private val limiter: BandwidthLimiter,
    private val stateLock: Any,
    private val onProgress: (Long) -> Unit,
    private val calls: MutableSet<Call>,
) {
    suspend fun download(initialResponse: Response? = null) {
        var first = initialResponse
        var attempt = 0
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            try {
                if (first != null) {
                    val response = first
                    first = null
                    stream(response, buffer)
                } else {
                    val offset = synchronized(stateLock) { segment.currentOffset }
                    if (offset > segment.endByte) break
                    if (item.metadata.validator == null) throw UnsafeResumeException("Cannot resume without a resource validator")
                    withDownloadResponse(client, DownloadHttp.request(item, offset, segment.endByte), calls) { response ->
                        DownloadHttp.validate(response, offset, segment.endByte, item.totalBytes, item.metadata.validator)
                        stream(response, buffer)
                    }
                }
                synchronized(stateLock) { segment.status = SegmentStatus.COMPLETED }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsafeResumeException) {
                throw e
            } catch (e: HttpDownloadException) {
                if (!e.retryable || attempt >= 4) throw e
                val seconds = e.retryAfter?.toLongOrNull()?.coerceIn(0, 300)
                delay(seconds?.times(1000) ?: (500L shl attempt))
            } catch (e: java.io.IOException) {
                if (attempt >= 4 || item.metadata.validator == null || item.totalBytes < 0) throw e
                delay(500L shl attempt)
            }
            attempt++
        }
        synchronized(stateLock) { segment.status = SegmentStatus.COMPLETED }
    }

    private suspend fun stream(response: Response, buffer: ByteArray) {
        DownloadHttp.checkStatus(response)
        DownloadHttp.checkEncoding(response)
        synchronized(stateLock) { segment.status = SegmentStatus.DOWNLOADING }
        val stream = response.body.byteStream()
        val diskBuffer = java.nio.ByteBuffer.wrap(buffer)
        while (true) {
            coroutineContext.ensureActive()
            val remaining = synchronized(stateLock) { segment.remainingBytes }
            if (segment.endByte != Long.MAX_VALUE && remaining == 0L) {
                if (segment.endByte == item.totalBytes - 1 && stream.read() != -1) {
                    throw UnsafeResumeException("Response contains bytes beyond the expected file length")
                }
                return
            }
            val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) {
                if (segment.endByte != Long.MAX_VALUE) throw EOFException("Response ended before the requested range was complete")
                synchronized(stateLock) {
                    segment.endByte = segment.currentOffset - 1
                    segment.status = SegmentStatus.COMPLETED
                }
                return
            }
            if (count == 0) continue
            limiter.throttle(count)
            coroutineContext.ensureActive()
            // Ranges never change while a worker runs, so disk I/O needs no task-wide lock.
            val position = synchronized(stateLock) { segment.currentOffset }
            diskBuffer.clear()
            diskBuffer.limit(count)
            writer.writeAt(position, diskBuffer)
            synchronized(stateLock) {
                segment.digest.update(buffer, 0, count)
                segment.downloadedBytes += count
            }
            onProgress(count.toLong())
        }
    }
}

internal suspend fun <T> withDownloadResponse(
    client: OkHttpClient,
    request: okhttp3.Request,
    calls: MutableSet<Call>,
    block: suspend (Response) -> T,
): T = coroutineScope {
    val call = client.newCall(request)
    synchronized(calls) { calls.add(call) }
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        call.awaitResponse().use { block(it) }
    } finally {
        synchronized(calls) { calls.remove(call) }
        cancellation.cancel()
    }
}
