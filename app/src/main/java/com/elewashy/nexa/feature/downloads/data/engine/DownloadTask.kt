package com.elewashy.nexa.feature.downloads.data.engine

import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.domain.usecase.LinkRefreshResult
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

class DownloadTask(
    val item: DownloadItem,
    private val client: OkHttpClient,
    private val onProgress: (DownloadTask) -> Unit = {},
    private val onStatusChange: (DownloadTask, DownloadStatus) -> Unit = { _, _ -> },
    private val restoredSegments: List<PersistedSegment> = emptyList(),
    private val bandwidthLimiter: BandwidthLimiter = BandwidthLimiter(),
    private val onMetadata: suspend (DownloadItem, String?) -> Unit = { _, _ -> },
    private val refreshLink: suspend (DownloadItem) -> LinkRefreshResult = { LinkRefreshResult.Unsupported },
) {
    companion object {
        const val ERROR_FINALIZE_FAILED = "ERR_DOWNLOAD_FINALIZE_FAILED"
        const val ERROR_FILE_MISSING = "ERR_DOWNLOADED_FILE_MISSING"
        const val ERROR_RESUME_UNREACHABLE = "ERR_RESUME_SERVER_UNREACHABLE"
        const val ERROR_RESUME_URL_DEAD = "ERR_RESUME_URL_DEAD"
        const val ERROR_STORAGE_PERMISSION = "ERR_STORAGE_PERMISSION_MISSING"
        const val ERROR_UNSAFE_RESUME = "ERR_UNSAFE_RESUME"
        const val ERROR_LINK_REFRESH_FAILED = "ERR_LINK_REFRESH_FAILED"
        const val ERROR_SOURCE_UNAVAILABLE = "ERR_SOURCE_UNAVAILABLE"
        const val PART_SUFFIX = ".part"

        internal fun rebuildSegmentsFromState(restoredSegments: List<PersistedSegment>, totalBytes: Long): List<DownloadSegment>? {
            if (restoredSegments.isEmpty() || totalBytes <= 0) return null
            var next = 0L
            val sorted = restoredSegments.sortedBy { it.startByte }
            for (state in sorted) {
                if (state.startByte != next || state.endByte < state.startByte || state.endByte >= totalBytes) return null
                val length = state.endByte - state.startByte + 1
                if (state.downloadedBytes !in 0..length || state.completed && state.downloadedBytes != length) return null
                next = state.endByte + 1
            }
            if (next != totalBytes) return null
            return sorted.mapIndexed { index, state ->
                DownloadSegment(index, state.startByte, state.endByte, state.downloadedBytes,
                    status = if (state.downloadedBytes == state.endByte - state.startByte + 1) SegmentStatus.COMPLETED else SegmentStatus.PENDING)
            }
        }
    }

    data class ProgressSnapshot(val downloadedBytes: Long, val totalBytes: Long, val segments: List<PersistedSegment>)
    private val stateLock = Any()
    private val calls = mutableSetOf<Call>()
    private var segments = emptyList<DownloadSegment>()
    @Volatile private var verifiedRestoredFile = false
    @Volatile private var job: Job? = null
    @Volatile private var stopped = false
    @Volatile private var writer: SegmentFileWriter? = null
    private val partFile get() = File(item.filePath + PART_SUFFIX)
    private var lastTick = 0L
    private var speedStart = 0L
    private var speedBytes = 0L

    fun start(parentScope: CoroutineScope) = launch(parentScope)
    fun resume(parentScope: CoroutineScope) = launch(parentScope)

    private fun launch(scope: CoroutineScope): Job {
        val previous = job
        if (previous?.isActive == true) return previous
        stopped = false
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                coroutineContext.ensureActive()
                item.errorMessage = null
                lastTick = 0
                item.downloadSpeedBytesPerSecond = 0
                speedStart = System.nanoTime()
                speedBytes = item.downloadedBytes
                if (segments.isEmpty() && restoredSegments.isNotEmpty()) {
                    synchronized(stateLock) {
                        segments = rebuildSegmentsFromState(restoredSegments, item.totalBytes)
                            ?: throw UnsafeResumeException("Invalid saved segment coverage")
                    }
                }
                try {
                    transfer()
                } catch (error: HttpDownloadException) {
                    if (error.code !in setOf(401, 403, 404, 410)) throw error
                    when (val result = refreshLink(item.copy())) {
                        is LinkRefreshResult.Success -> {
                            coroutineContext.ensureActive()
                            item.metadata = item.metadata.copy(resolvedUrl = result.url)
                            onMetadata(item, null)
                            writer?.close()
                            writer = null
                            transfer()
                        }
                        LinkRefreshResult.Failed -> {
                            item.errorMessage = ERROR_LINK_REFRESH_FAILED
                            throw error
                        }
                        LinkRefreshResult.Unavailable -> {
                            item.errorMessage = ERROR_SOURCE_UNAVAILABLE
                            throw error
                        }
                        LinkRefreshResult.Unsupported -> throw error
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                if (!stopped) {
                    item.errorMessage = when (e) {
                        is UnsafeResumeException -> ERROR_UNSAFE_RESUME
                        is HttpDownloadException -> item.errorMessage ?: if (!e.retryable) ERROR_RESUME_URL_DEAD else e.message
                        else -> item.errorMessage ?: e.message ?: ERROR_RESUME_UNREACHABLE
                    }
                    updateStatus(DownloadStatus.FAILED)
                }
            } finally {
                item.downloadedBytes = synchronized(stateLock) {
                    if (segments.isNotEmpty()) segments.sumOf { it.effectiveDownloadedBytes } else item.downloadedBytes
                }
                item.downloadSpeedBytesPerSecond = 0
                try { writer?.close() } finally { writer = null }
            }
        }
        return job!!
    }

    private suspend fun transfer() {
        if (item.downloadedBytes > 0 || segments.any { it.downloadedBytes > 0 } ||
            segments.isNotEmpty() && segments.all { it.status == SegmentStatus.COMPLETED }) resumeExisting() else fresh()
    }

    fun pause() {
        synchronized(stateLock) { stopped = true }
        job?.cancel()
        cancelCalls()
        synchronized(stateLock) { item.downloadedBytes = segments.sumOf { it.effectiveDownloadedBytes }.takeIf { segments.isNotEmpty() } ?: item.downloadedBytes }
        item.downloadSpeedBytesPerSecond = 0
        updateStatus(DownloadStatus.PAUSED)
    }

    fun cancel(cleanupScope: CoroutineScope? = null): Job {
        synchronized(stateLock) { stopped = true }
        job?.cancel()
        cancelCalls()
        val previous = job
        val path = partFile
        val cleanup = (cleanupScope ?: CoroutineScope(Dispatchers.IO)).launch(Dispatchers.IO) {
            previous?.join()
            path.delete()
        }
        updateStatus(DownloadStatus.CANCELLED)
        return cleanup
    }

    @Suppress("UNUSED_PARAMETER")
    fun cleanup(cleanupScope: CoroutineScope? = null): Job? {
        synchronized(stateLock) { stopped = true }
        job?.cancel()
        cancelCalls()
        return job
    }

    private fun cancelCalls() = synchronized(calls) { calls.forEach(Call::cancel) }

    private suspend fun fresh() {
        updateStatus(DownloadStatus.PENDING)
        withDownloadResponse(client, DownloadHttp.request(item), calls) { response ->
            DownloadHttp.checkStatus(response)
            if (response.code != 200) throw UnsafeResumeException("Unexpected initial response ${response.code}")
            DownloadHttp.checkEncoding(response)
            val total = response.body.contentLength()
            item.totalBytes = total
            item.metadata = item.metadata.copy(
                etag = response.header("ETag"), lastModified = DownloadHttp.strongLastModified(response),
                resolvedUrl = response.request.url.toString(),
            )
            onMetadata(item, response.header("Content-Disposition"))
            coroutineContext.ensureActive()
            val canSplit = total >= 1024 * 1024 && item.metadata.validator != null && response.header("Accept-Ranges").equals("bytes", true)
            synchronized(stateLock) {
                segments = SegmentPlan.createSingleSegment(total)
                item.downloadedBytes = 0
            }
            if (partFile.exists() && !partFile.delete()) throw IOException("Cannot clear stale partial file")
            val output = SegmentFileWriter(partFile.path, total)
            writer = output
            // setLength is sparse sizing, not physical disk-space reservation.
            if (total > 0) output.preAllocate()
            updateStatus(DownloadStatus.DOWNLOADING)
            if (canSplit) {
                val sampleSize = 256 * 1024L
                val sample = DownloadSegment(0, 0, sampleSize - 1)
                synchronized(stateLock) { segments = listOf(sample, DownloadSegment(1, sampleSize, total - 1)) }
                val started = System.nanoTime()
                worker(sample, output).download(response)
                val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
                val fast = sampleSize.toDouble() * 1_000_000_000 / elapsed >= 32 * 1024 * 1024
                val remainder = if (fast) SegmentPlan.createSingleSegment(total - sampleSize)
                    else SegmentPlan.createSegments(total - sampleSize)
                synchronized(stateLock) {
                    segments = listOf(sample) + remainder.mapIndexed { index, segment ->
                        DownloadSegment(index + 1, sampleSize + segment.startByte, sampleSize + segment.endByte)
                    }
                }
            }
            val activeSegments = segments.filter { it.hasRemainingBytes }
            val rangeRefused = java.util.concurrent.atomic.AtomicBoolean(false)
            coroutineScope {
                activeSegments.forEachIndexed { index, segment ->
                    launch {
                        try { worker(segment, output).download(if (index == 0) response else null) }
                        catch (e: UnsafeResumeException) {
                            if (index == 0) throw e
                            rangeRefused.set(true)
                        } catch (e: HttpDownloadException) {
                            if (index == 0 || e.retryable) throw e
                            rangeRefused.set(true)
                        }
                    }
                }
            }
            if (rangeRefused.get()) {
                // The original GET is still open at the first range boundary. Reuse it,
                // overwriting all later ranges from that same representation without another GET.
                val first = activeSegments.first()
                val tail = DownloadSegment(first.id + 1, first.endByte + 1, total - 1)
                synchronized(stateLock) { segments = segments.takeWhile { it.startByte <= first.startByte } + tail }
                worker(tail, output).download(response)
            }
            finalize(output)
        }
    }

    private suspend fun resumeExisting() {
        if (segments.isEmpty()) throw UnsafeResumeException("No verified partial-file map")
        item.downloadedBytes = synchronized(stateLock) { segments.sumOf { it.effectiveDownloadedBytes } }
        if (!partFile.isFile) {
            // Recovery after a successful move followed by a process kill before the DB commit.
            if (segments.all { it.status == SegmentStatus.COMPLETED } && File(item.filePath).isFile && File(item.filePath).length() == item.totalBytes) {
                updateStatus(DownloadStatus.COMPLETED)
                return
            }
            throw UnsafeResumeException("Partial file missing")
        }
        verifyRestoredBytes()
        val expected = synchronized(stateLock) { segments.filter { it.downloadedBytes > 0 }.maxOfOrNull { it.startByte + it.downloadedBytes } ?: 0L }
        if (partFile.length() < expected) throw UnsafeResumeException("Partial file is shorter than saved progress")
        if (segments.any { it.hasRemainingBytes } && (item.metadata.validator == null || item.totalBytes <= 0)) {
            throw UnsafeResumeException("Resource has no safe resume validator/length; partial data preserved")
        }
        val output = SegmentFileWriter(partFile.path, item.totalBytes)
        writer = output
        updateStatus(DownloadStatus.DOWNLOADING)
        coroutineScope {
            segments.filter { it.hasRemainingBytes }.forEach { segment -> launch { worker(segment, output).download() } }
        }
        finalize(output)
    }

    private suspend fun verifyRestoredBytes() {
        if (verifiedRestoredFile || restoredSegments.isEmpty()) return
        java.io.RandomAccessFile(partFile, "r").use { file ->
            val buffer = ByteArray(64 * 1024)
            restoredSegments.filter { it.downloadedBytes > 0 }.forEach { state ->
                val expected = state.sha256 ?: throw UnsafeResumeException("Saved partial bytes have no integrity checkpoint")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.seek(state.startByte)
                var remaining = state.downloadedBytes
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    file.readFully(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
                val checksum = (digest.clone() as java.security.MessageDigest).digest().joinToString("") { "%02x".format(it) }
                if (checksum != expected) {
                    throw UnsafeResumeException("Saved partial bytes failed integrity validation")
                }
                synchronized(stateLock) { segments.first { it.startByte == state.startByte }.digest = digest }
            }
        }
        verifiedRestoredFile = true
    }

    private fun worker(segment: DownloadSegment, output: SegmentFileWriter) =
        SegmentDownloader(client, item, segment, output, bandwidthLimiter, stateLock, { progress() }, calls)

    private fun progress() {
        synchronized(stateLock) {
            val now = System.nanoTime()
            if (now - lastTick < 250_000_000) return
            lastTick = now
            item.downloadedBytes = segments.sumOf { it.effectiveDownloadedBytes }
            val elapsed = now - speedStart
            if (elapsed >= 1_000_000_000) {
                item.downloadSpeedBytesPerSecond = ((item.downloadedBytes - speedBytes).toDouble() * 1_000_000_000 / elapsed).toLong()
                speedStart = now
                speedBytes = item.downloadedBytes
            }
            item.etaSeconds = if (item.totalBytes > 0 && item.downloadSpeedBytesPerSecond > 0)
                (item.totalBytes - item.downloadedBytes) / item.downloadSpeedBytesPerSecond else -1
        }
        onProgress(this)
    }

    fun snapshotProgress(allowRestoredFallback: Boolean = true): ProgressSnapshot = synchronized(stateLock) {
        val current = if (segments.isNotEmpty()) segments.map { segment ->
            PersistedSegment(segment.startByte, segment.endByte, segment.effectiveDownloadedBytes, segment.status == SegmentStatus.COMPLETED,
                if (restoredSegments.isNotEmpty() && !verifiedRestoredFile) restoredSegments.firstOrNull { it.startByte == segment.startByte }?.sha256 else segment.checksum())
        } else if (allowRestoredFallback) restoredSegments else emptyList()
        ProgressSnapshot(if (current.isNotEmpty()) current.sumOf { it.downloadedBytes } else item.downloadedBytes, item.totalBytes,
            current.filter { it.endByte >= it.startByte && it.endByte < Long.MAX_VALUE })
    }

    private suspend fun finalize(output: SegmentFileWriter) {
        coroutineContext.ensureActive()
        val bytes = synchronized(stateLock) { segments.sumOf { it.effectiveDownloadedBytes } }
        if (item.totalBytes >= 0 && bytes != item.totalBytes) throw IOException("Incomplete download: $bytes/${item.totalBytes}")
        output.force()
        output.close()
        writer = null
        coroutineContext.ensureActive()
        if (stopped) return
        if (partFile.length() != bytes) throw IOException("Output length mismatch")
        // No REPLACE_EXISTING: never delete an unrelated file that claimed the target name.
        synchronized(stateLock) {
            if (stopped) return
            try { Files.move(partFile.toPath(), File(item.filePath).toPath()) }
            catch (e: IOException) { item.errorMessage = ERROR_FINALIZE_FAILED; throw e }
            item.totalBytes = bytes
            item.downloadedBytes = bytes
            item.downloadSpeedBytesPerSecond = 0
            item.etaSeconds = 0
            updateStatus(DownloadStatus.COMPLETED)
        }
    }

    private fun updateStatus(status: DownloadStatus) {
        item.status = status
        onStatusChange(this, status)
    }
}
