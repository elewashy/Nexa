package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the complete lifecycle of a single file download:
 *  1. Probes the server (HEAD / GET-Range) to determine file size and range support.
 *  2. Splits the file into segments (or uses a single segment if ranges aren't supported).
 *  3. Pre-allocates the file on disk.
 *  4. Launches each segment as a child coroutine (parallel download).
 *  5. Aggregates progress from all segments and reports to the caller.
 *  6. Handles dynamic segment splitting when segments finish early.
 *  7. Supports pause/resume/cancel at any time.
 *
 * Architecture:
 *  - Uses [SupervisorJob] so that one failed segment doesn't cancel siblings.
 *  - Each [SegmentDownloader] runs in its own coroutine on [Dispatchers.IO].
 *  - Progress is aggregated from all segments and pushed to the [onProgress] callback.
 *
 * @property item         The [DownloadItem] metadata (URL, headers, file path, etc.).
 * @property client       Shared OkHttp client.
 * @property onProgress   Progress callback with (downloadedBytes, totalBytes, speedBps).
 * @property onStatusChange Status change callback with the new [DownloadStatus].
 */
class DownloadTask(
    val item: DownloadItem,
    private val client: OkHttpClient,
    private val onProgress: (DownloadTask) -> Unit = {},
    private val onStatusChange: (DownloadTask, DownloadStatus) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "DownloadTask"

        /** Minimum interval between progress updates to avoid UI flooding. */
        private const val PROGRESS_THROTTLE_MS = 250L
    }

    // ── State ───────────────────────────────────────────────────────────

    /** Coroutine scope for this task — cancelled on cancel/pause. */
    private var taskScope: CoroutineScope? = null

    /** File writer shared by all segments. */
    private var fileWriter: SegmentFileWriter? = null

    /** Active download segments. */
    private val segments = mutableListOf<DownloadSegment>()
    private val segmentsMutex = Mutex()

    /**
     * Active [SegmentDownloader] instances.
     * Tracked so that [cancel]/[pause] can immediately cancel their OkHttp calls,
     * interrupting any blocking `stream.read()` without waiting for timeout.
     */
    private val activeDownloaders = mutableListOf<SegmentDownloader>()

    /** Tracks whether server supports Range requests. */
    private var supportsRanges = false

    /** Set after a resume range re-probe fails — skip future re-probes. */
    private var rangeProbeConfirmedFailed = false

    /** Next segment ID for dynamic splitting. */
    private var nextSegmentId = 0

    /**
     * Bytes already downloaded in a previous session (before app restart).
     * Set by [resumeAfterRestart] so that progress aggregation correctly
     * adds pre-existing bytes to the segment's session-only count.
     */
    private var baseDownloadedBytes = 0L

    // ── Speed calculation ───────────────────────────────────────────────

    /** Total bytes reported by segments since last speed calculation. */
    private val recentBytesDownloaded = AtomicLong(0)

    /** Timestamp of last speed calculation. */
    @Volatile private var lastSpeedCalcTime = 0L

    /** Current calculated speed in bytes/second. */
    @Volatile private var currentSpeedBps = 0L

    /** Timestamp of last progress callback. */
    @Volatile private var lastProgressTime = 0L

    /** Timestamp of last time actual bytes were received (for stall detection). */
    @Volatile private var lastBytesReceivedTime = 0L

    // ===================================================================
    //  Public API
    // ===================================================================

    /**
     * Starts the download.
     *
     * @param parentScope  The coroutine scope from which to launch the task.
     *                     Typically [DownloadEngine]'s scope. The task creates
     *                     a child scope with [SupervisorJob] so segment failures
     *                     are independent.
     */
    fun start(parentScope: CoroutineScope) {
        val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
        taskScope = CoroutineScope(supervisorJob + Dispatchers.IO)

        updateStatus(DownloadStatus.PENDING)

        taskScope!!.launch {
            try {
                executeDownload()
            } catch (e: CancellationException) {
                Log.d(TAG, "Task cancelled: ${item.fileName}")
                // Status already set by pause/cancel
            } catch (e: NetworkLostException) {
                Log.w(TAG, "Network lost during download: ${item.fileName}")
                handleNetworkLoss()
            } catch (e: Exception) {
                Log.e(TAG, "Task failed: ${item.fileName} — ${e.message}", e)
                updateStatus(DownloadStatus.FAILED)
            }
        }
    }

    /**
     * Pauses the download.
     * Segments stop at their current positions; resume will continue from there.
     */
    fun pause() {
        Log.d(TAG, "Pausing: ${item.fileName}")

        // Cancel active OkHttp calls FIRST — this interrupts blocking stream.read()
        // calls immediately, so segments stop within milliseconds.
        cancelAllActiveCalls()

        // Now cancel the coroutine scope
        taskScope?.cancel()
        taskScope = null

        // Mark remaining segments as paused
        segments.forEach { seg ->
            if (seg.status == SegmentStatus.DOWNLOADING || seg.status == SegmentStatus.PENDING) {
                seg.status = SegmentStatus.PAUSED
            }
        }

        item.downloadSpeedBytesPerSecond = 0
        updateStatus(DownloadStatus.PAUSED)
    }

    /**
     * Resumes the download from where it left off.
     * Only segments with remaining bytes are restarted.
     *
     * If segments are empty (e.g., after service restart + restore from persistence),
     * the download is re-probed and re-segmented, starting from scratch but the
     * file will be overwritten (this is safe because segment state wasn't persisted).
     */
    fun resume(parentScope: CoroutineScope) {
        Log.d(TAG, "Resuming: ${item.fileName} " +
                "(segments=${segments.size}, downloaded=${item.downloadedBytes})")

        item.failureCount = 0

        // Reset speed counters to avoid stale speed readings after resume
        resetSpeedCounters()

        // Save downloaded bytes before any reset — needed for range re-probe
        val pausedAtBytes = item.downloadedBytes

        val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
        taskScope = CoroutineScope(supervisorJob + Dispatchers.IO)

        updateStatus(DownloadStatus.DOWNLOADING)

        taskScope!!.launch {
            try {
                when {
                    // Case 1: We have segments with remaining bytes (normal pause/resume)
                    segments.isNotEmpty() && segments.any { it.hasRemainingBytes } -> {
                        resumeSegments(pausedAtBytes)
                    }
                    // Case 2: No segments but partial file exists (app restart with progress)
                    segments.isEmpty() && item.downloadedBytes > 0 && item.totalBytes > 0 -> {
                        Log.d(TAG, "Resuming after app restart: ${item.fileName} " +
                                "(${item.downloadedBytes}/${item.totalBytes} bytes)")
                        resumeAfterRestart()
                    }
                    // Case 3: No segments, no progress — full fresh download
                    else -> {
                        Log.d(TAG, "No segments or progress, starting fresh: ${item.fileName}")
                        executeDownload()
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Task cancelled during resume: ${item.fileName}")
            } catch (e: NetworkLostException) {
                Log.w(TAG, "Network lost during resume: ${item.fileName}")
                handleNetworkLoss()
            } catch (e: Exception) {
                Log.e(TAG, "Resume failed: ${item.fileName} — ${e.message}", e)
                updateStatus(DownloadStatus.FAILED)
            }
        }
    }

    /**
     * Cancels the download permanently and deletes the partial file.
     *
     * File deletion is done **synchronously** on the calling thread to avoid
     * being lost if a coroutine scope is cancelled (e.g., service destruction
     * shortly after the last download is cancelled).
     */
    fun cancel(cleanupScope: CoroutineScope? = null) {
        Log.d(TAG, "Cancelling: ${item.fileName}")

        // Cancel active OkHttp calls FIRST — this interrupts blocking stream.read()
        // so segments stop within milliseconds instead of waiting for timeout.
        cancelAllActiveCalls()

        // Now cancel the coroutine scope
        taskScope?.cancel()
        taskScope = null

        // Close and delete the file SYNCHRONOUSLY.
        // We cannot rely on async deletion — if the service is being destroyed
        // (e.g., this was the last download), the engineScope coroutine would be
        // cancelled before the delete runs, leaving orphaned files on disk.
        val writer = fileWriter
        fileWriter = null
        writer?.deleteFile()

        updateStatus(DownloadStatus.CANCELLED)
    }

    /**
     * Cleans up resources. Call when removing the task from the engine.
     *
     * @param cleanupScope  Scope for async file-handle cleanup.
     */
    fun cleanup(cleanupScope: CoroutineScope? = null) {
        cancelAllActiveCalls()

        taskScope?.cancel()
        taskScope = null

        val scope = cleanupScope ?: CoroutineScope(Dispatchers.IO)
        val writer = fileWriter
        fileWriter = null
        scope.launch(Dispatchers.IO) {
            writer?.close()
        }
    }

    /**
     * Cancels all active OkHttp calls across all segments.
     * This interrupts blocking `stream.read()` immediately, so segments
     * don't hold the file handle until the 120-second read timeout.
     */
    private fun cancelAllActiveCalls() {
        synchronized(activeDownloaders) {
            activeDownloaders.forEach { it.cancelActiveCall() }
            activeDownloaders.clear()
        }
    }

    // ===================================================================
    //  Internal download logic
    // ===================================================================

    /**
     * Main download orchestration (fresh download).
     * 1. Probe server → 2. Plan segments → 3. Pre-allocate → 4. Download
     */
    private suspend fun executeDownload() {
        // Fresh download — no base bytes from a previous session
        baseDownloadedBytes = 0

        // Build header map from DownloadItem metadata
        val headers = buildHeaderMap()

        // ── Step 1: Probe the server ─────────────────────────────────────
        Log.d(TAG, "Probing: ${item.url}")
        val probeResult = HttpProber.probe(client, item.url, headers)

        val totalSize = probeResult.contentLength
        supportsRanges = probeResult.supportsRanges
        item.totalBytes = totalSize

        Log.d(TAG, "Probe result: size=${totalSize}, ranges=$supportsRanges")

        // ── Step 2: Plan segments ────────────────────────────────────────
        val initialSegments = if (supportsRanges && totalSize > 0) {
            SegmentPlan.createSegments(totalSize)
        } else {
            SegmentPlan.createSingleSegment(totalSize)
        }

        segmentsMutex.withLock {
            segments.clear()
            segments.addAll(initialSegments)
            nextSegmentId = segments.size
        }

        // ── Step 3: Pre-allocate file ────────────────────────────────────
        val writer = SegmentFileWriter(item.filePath, totalSize)
        fileWriter = writer

        if (totalSize > 0) {
            writer.preAllocate()
        }

        // ── Step 4: Download all segments ────────────────────────────────
        updateStatus(DownloadStatus.DOWNLOADING)
        lastSpeedCalcTime = System.currentTimeMillis()

        try {
            downloadAllSegments(writer, headers)
        } catch (e: RangeNotSupportedException) {
            Log.w(TAG, "Server doesn't actually support ranges, falling back to single stream: ${item.fileName}")
            
            // Disable range support and clear segments
            supportsRanges = false
            segmentsMutex.withLock {
                segments.clear()
                segments.addAll(SegmentPlan.createSingleSegment(item.totalBytes))
                nextSegmentId = segments.size
            }
            
            // Reset state for single-stream retry
            item.downloadedBytes = 0
            baseDownloadedBytes = 0
            
            // Restart the download with the new single-segment plan
            downloadAllSegments(writer, headers)
        }
    }

    /**
     * Resumes a download after the app was restarted (segments lost from memory).
     *
     * Re-probes the server to check range support. If ranges are supported,
     * creates a single segment from [item.downloadedBytes] to [item.totalBytes],
     * preserving the partially downloaded file. If not, has to restart from scratch.
     */
    private suspend fun resumeAfterRestart() {
        val headers = buildHeaderMap()

        // Re-probe the server to verify range support and current file size
        Log.d(TAG, "Re-probing for resume: ${item.url}")
        val probeResult = HttpProber.probe(client, item.url, headers)

        supportsRanges = probeResult.supportsRanges

        // If server reports a size, validate it matches what we had
        if (probeResult.contentLength > 0) {
            if (item.totalBytes > 0 && probeResult.contentLength != item.totalBytes) {
                Log.w(TAG, "File size changed on server " +
                        "(was ${item.totalBytes}, now ${probeResult.contentLength}). " +
                        "Starting fresh.")
                item.downloadedBytes = 0
                executeDownload()
                return
            }
            item.totalBytes = probeResult.contentLength
        }

        if (!supportsRanges) {
            // Server doesn't support ranges — must restart from scratch
            Log.w(TAG, "Server doesn't support ranges. Starting fresh: ${item.fileName}")
            item.downloadedBytes = 0
            executeDownload()
            return
        }

        // Verify the partial file still exists on disk
        val file = java.io.File(item.filePath)
        if (!file.exists()) {
            Log.w(TAG, "Partial file missing. Starting fresh: ${item.fileName}")
            item.downloadedBytes = 0
            executeDownload()
            return
        }

        // Track base bytes so progress aggregation is correct
        baseDownloadedBytes = item.downloadedBytes

        // Create multiple segments for the remaining byte range (parallel downloads)
        val remainingStart = item.downloadedBytes
        val remainingEnd = item.totalBytes - 1
        val resumeSegments = SegmentPlan.createSegmentsForRange(remainingStart, remainingEnd)

        segmentsMutex.withLock {
            segments.clear()
            segments.addAll(resumeSegments)
            nextSegmentId = resumeSegments.size
        }

        // Open file writer without pre-allocation (file already exists)
        val writer = SegmentFileWriter(item.filePath, item.totalBytes)
        fileWriter = writer

        val remainingKb = (remainingEnd - remainingStart + 1) / 1024
        Log.d(TAG, "Resuming from byte ${item.downloadedBytes} / ${item.totalBytes} " +
                "(${remainingKb}KB remaining, ${resumeSegments.size} segments)")

        lastSpeedCalcTime = System.currentTimeMillis()

        try {
            downloadAllSegments(writer, headers)
        } catch (e: RangeNotSupportedException) {
            Log.w(TAG, "Server doesn't actually support ranges on restart-resume, falling back: ${item.fileName}")
            supportsRanges = false
            item.downloadedBytes = 0
            baseDownloadedBytes = 0
            executeDownload()
        }
    }

    /**
     * Resumes downloading segments that still have remaining bytes.
     */
    private suspend fun resumeSegments(pausedAtBytes: Long = 0) {
        val headers = buildHeaderMap()
        val writer = fileWriter ?: run {
            // Re-create file writer if it was closed
            val w = SegmentFileWriter(item.filePath, item.totalBytes)
            fileWriter = w
            w
        }

        // If server was marked as non-range, re-probe before giving up.
        // Some servers fail the initial range probe (e.g., they return 200 for
        // bytes=0-0 but correctly return 206 for non-zero offsets). Re-probing
        // with the actual resume offset gives them a second chance.
        if (!supportsRanges && !rangeProbeConfirmedFailed && pausedAtBytes > 0 && item.totalBytes > 0) {
            val probed = tryRangeProbeForResume(headers, pausedAtBytes)
            if (probed) {
                Log.d(TAG, "Re-probe succeeded — server supports ranges after all: ${item.fileName}")
                supportsRanges = true
                rangeProbeConfirmedFailed = false

                // Rebuild segments for the remaining range using parallel segments
                val resumeSegments = SegmentPlan.createSegmentsForRange(
                    pausedAtBytes, item.totalBytes - 1
                )
                baseDownloadedBytes = pausedAtBytes
                item.downloadedBytes = pausedAtBytes

                segmentsMutex.withLock {
                    segments.clear()
                    segments.addAll(resumeSegments)
                    nextSegmentId = resumeSegments.size
                }

                lastSpeedCalcTime = System.currentTimeMillis()
                try {
                    downloadAllSegments(writer, headers)
                } catch (e: RangeNotSupportedException) {
                    // Re-probe said 206 but full download got 200 — fall back
                    Log.w(TAG, "Range re-probe was wrong, falling back: ${item.fileName}")
                    supportsRanges = false
                    rangeProbeConfirmedFailed = true
                    item.downloadedBytes = 0
                    baseDownloadedBytes = 0
                    executeDownload()
                }
                return
            } else {
                Log.d(TAG, "Re-probe failed — server truly doesn't support ranges: ${item.fileName}")
                rangeProbeConfirmedFailed = true
            }
        }

        // Reset retry counts for paused/failed segments
        segments.forEach { seg ->
            if (seg.status == SegmentStatus.PAUSED || seg.status == SegmentStatus.FAILED) {
                seg.retryCount = 0
                seg.status = SegmentStatus.PENDING
                // For non-range servers, we keep seg.downloadedBytes as-is.
                // SegmentDownloader will skip the already-downloaded bytes
                // from the response stream, preserving progress.
            }
        }

        lastSpeedCalcTime = System.currentTimeMillis()
        downloadAllSegments(writer, headers)
    }

    /**
     * Attempts a lightweight range probe using the actual resume offset.
     * Returns `true` if the server responds with 206 Partial Content,
     * meaning it genuinely supports ranges despite possibly failing the
     * initial HEAD-based probe.
     */
    private suspend fun tryRangeProbeForResume(headers: Map<String, String>, offset: Long): Boolean {
        return try {
            val requestBuilder = okhttp3.Request.Builder()
                .url(item.url)
                .get()
            for ((key, value) in headers) {
                requestBuilder.addHeader(key, value)
            }
            requestBuilder.addHeader("Range", "bytes=$offset-$offset")
            val call = client.newCall(requestBuilder.build())
            call.execute().use { resp ->
                Log.d(TAG, "Resume range probe: GET bytes=$offset-$offset → ${resp.code}")
                resp.code == 206
            }
        } catch (e: Exception) {
            Log.w(TAG, "Resume range probe failed: ${e.message}")
            false
        }
    }

    /**
     * Launches all pending/paused segments as parallel coroutines
     * and waits for all of them to complete.
     */
    private suspend fun downloadAllSegments(
        writer: SegmentFileWriter,
        headers: Map<String, String>
    ) {
        val segmentsToDownload = segmentsMutex.withLock {
            segments.filter { it.hasRemainingBytes }.toList()
        }

        if (segmentsToDownload.isEmpty()) {
            handleAllSegmentsCompleted()
            return
        }

        // coroutineScope ensures we wait for all child coroutines (initial AND dynamic splits)
        coroutineScope {
            segmentsToDownload.forEach { segment ->
                startSegmentJob(this, writer, headers, segment)
            }
        }

        // All segments (including dynamic ones) have finished execution.
        // Check final status to determine if we succeeded, failed, or were paused.
        val allCompleted = segmentsMutex.withLock {
            segments.all { it.status == SegmentStatus.COMPLETED }
        }

        if (allCompleted) {
            handleAllSegmentsCompleted()
        } else {
            // Check if any segment was paused due to network loss
            val anyNetworkPaused = segmentsMutex.withLock {
                segments.any { it.status == SegmentStatus.PAUSED }
            }

            if (anyNetworkPaused) {
                throw NetworkLostException("One or more segments lost network")
            }

            val anyFailed = segmentsMutex.withLock {
                segments.any { it.status == SegmentStatus.FAILED }
            }
            if (anyFailed) {
                item.failureCount++
                if (item.failureCount >= 2) {
                    updateStatus(DownloadStatus.PAUSED)
                } else {
                    updateStatus(DownloadStatus.FAILED)
                }
            }
        }
    }

    /**
     * Helper to launch a single segment downloader coroutine.
     */
    private fun startSegmentJob(
        scope: CoroutineScope,
        writer: SegmentFileWriter,
        headers: Map<String, String>,
        segment: DownloadSegment
    ) {
        scope.launch {
            val downloader = SegmentDownloader(
                client = client,
                url = item.url,
                headers = headers,
                segment = segment,
                fileWriter = writer,
                supportsRange = supportsRanges,
                onProgress = { _, bytesWritten ->
                    handleSegmentProgress(bytesWritten)
                }
            )

            synchronized(activeDownloaders) { activeDownloaders.add(downloader) }

            try {
                downloader.download()
            } finally {
                synchronized(activeDownloaders) { activeDownloaders.remove(downloader) }
            }

            // When a segment completes, try dynamic splitting.
            // Launch the new segment in the SAME scope so downloadAllSegments waits for it.
            if (segment.status == SegmentStatus.COMPLETED && supportsRanges) {
                tryDynamicSplit(scope, writer, headers)
            }
        }
    }

    /**
     * Attempts to dynamically split the segment with the most remaining bytes
     * and launch a new downloader for the split portion.
     *
     * The split is safe because [SegmentPlan.trySplitLargestSegment] shrinks
     * the existing segment's [DownloadSegment.endByte] before returning,
     * ensuring non-overlapping byte ranges.
     */
    private suspend fun tryDynamicSplit(
        scope: CoroutineScope,
        writer: SegmentFileWriter,
        headers: Map<String, String>
    ) {
        val newSeg: DownloadSegment

        segmentsMutex.withLock {
            val activeSegments = segments.filter { it.status == SegmentStatus.DOWNLOADING }
            val result = SegmentPlan.trySplitLargestSegment(activeSegments, nextSegmentId)
                ?: return

            val (_, newSegment) = result

            segments.add(newSegment)
            nextSegmentId++
            newSeg = newSegment
        }

        // Launch the new segment in the same scope provided by downloadAllSegments
        startSegmentJob(scope, writer, headers, newSeg)
    }

    /**
     * Called by each segment's progress callback.
     * Aggregates bytes from all segments and computes download speed.
     *
     * Speed calculation uses a simple interval approach:
     *  - Accumulate bytes in [recentBytesDownloaded] (AtomicLong, thread-safe)
     *  - Every ≥1 second, convert accumulated bytes to bytes/second
     *  - If no bytes flow for >2 seconds, force speed to 0
     */
    private fun handleSegmentProgress(bytesWritten: Long) {
        recentBytesDownloaded.addAndGet(bytesWritten)

        val now = System.currentTimeMillis()

        // Track when we last actually received bytes (for stall detection)
        if (bytesWritten > 0) {
            lastBytesReceivedTime = now
        }

        if (now - lastProgressTime < PROGRESS_THROTTLE_MS) return
        lastProgressTime = now

        // Aggregate downloaded bytes from all segments + pre-existing bytes
        val totalDownloaded = baseDownloadedBytes + segments.sumOf { it.effectiveDownloadedBytes }
        item.downloadedBytes = totalDownloaded

        // Calculate speed — 1-second window
        val elapsed = now - lastSpeedCalcTime
        if (elapsed >= 1000) {
            val bytes = recentBytesDownloaded.getAndSet(0)
            currentSpeedBps = if (elapsed > 0) (bytes * 1000) / elapsed else 0
            item.downloadSpeedBytesPerSecond = currentSpeedBps
            lastSpeedCalcTime = now
        }

        // Force speed to 0 if no bytes have flowed for >2 seconds (stall detection)
        // This runs independently of the 1-second speed window
        if (lastBytesReceivedTime > 0 && (now - lastBytesReceivedTime) > 2000) {
            currentSpeedBps = 0
            item.downloadSpeedBytesPerSecond = 0
        }

        // Calculate ETA — remaining bytes / current speed
        if (currentSpeedBps > 0 && item.totalBytes > 0) {
            val remainingBytes = item.totalBytes - totalDownloaded
            item.etaSeconds = if (remainingBytes > 0) remainingBytes / currentSpeedBps else 0
        } else {
            item.etaSeconds = -1 // Unknown
        }

        onProgress(this)
    }

    /**
     * Handles network loss by pausing all segments and setting the
     * wasWaitingForNetwork flag so the service knows to auto-resume.
     */
    private fun handleNetworkLoss() {
        // Cancel any ongoing work
        taskScope?.cancel()
        taskScope = null

        // Mark all active segments as paused
        segments.forEach { seg ->
            if (seg.status == SegmentStatus.DOWNLOADING || seg.status == SegmentStatus.PENDING) {
                seg.status = SegmentStatus.PAUSED
            }
        }

        // Set flag so DownloadService can auto-resume when network returns
        item.wasWaitingForNetwork = true
        item.downloadSpeedBytesPerSecond = 0

        Log.d(TAG, "Network lost — auto-pausing: ${item.fileName} " +
                "(${segments.count { it.hasRemainingBytes }} segments paused)")

        updateStatus(DownloadStatus.PAUSED)
    }

    /**
     * Resets all speed-related counters so that resume starts
     * with a clean speed measurement. Without this, the first
     * speed reading after resume would include stale accumulated data.
     */
    private fun resetSpeedCounters() {
        recentBytesDownloaded.set(0)
        lastSpeedCalcTime = System.currentTimeMillis()
        lastProgressTime = 0L
        lastBytesReceivedTime = 0L
        currentSpeedBps = 0
        item.downloadSpeedBytesPerSecond = 0
        item.etaSeconds = -1
    }

    /**
     * Called when all segments have completed successfully.
     */
    private suspend fun handleAllSegmentsCompleted() {
        // Aggregate actual bytes from segments + base bytes from previous session
        val actualDownloaded = baseDownloadedBytes + segments.sumOf { it.effectiveDownloadedBytes }

        if (item.totalBytes > 0) {
            item.downloadedBytes = item.totalBytes
        } else {
            // Unknown-size download — use the actual sum from segments
            item.downloadedBytes = actualDownloaded
            item.totalBytes = actualDownloaded  // Now we know the total
        }

        item.downloadSpeedBytesPerSecond = 0
        item.failureCount = 0

        // Close the file writer
        fileWriter?.close()

        updateStatus(DownloadStatus.COMPLETED)
        Log.d(TAG, "Download completed: ${item.fileName} " +
                "(${actualDownloaded / 1024}KB)")
    }

    /**
     * Updates [item.status] and notifies the listener.
     */
    private fun updateStatus(status: DownloadStatus) {
        item.status = status
        onStatusChange(this, status)
    }

    /**
     * Builds the HTTP header map from [DownloadItem] metadata.
     * Includes security headers required by video hosting services.
     */
    private fun buildHeaderMap(): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        item.userAgent?.let { headers["User-Agent"] = it }
            ?: run { headers["User-Agent"] = "Mozilla/5.0" }
        item.referer?.let { headers["Referer"] = it }
        item.origin?.let { headers["Origin"] = it }
        item.cookies?.let { headers["Cookie"] = it }

        // Accept any content type — we're downloading files, not browsing pages.
        // Using text/html first can cause CDNs to serve error pages instead of files.
        headers["Accept"] = "*/*"
        headers["Accept-Language"] = "en-US,en;q=0.9"

        // CRITICAL: Explicitly disable gzip to prevent file corruption.
        // OkHttp adds Accept-Encoding: gzip by default. If a server mistakenly
        // serves gzip-compressed content for a 206 Range response, OkHttp
        // silently decompresses it — writing MORE bytes than expected at wrong
        // file positions, corrupting the file. Setting "identity" prevents this.
        // Bonus: avoids wasting CPU on decompressing incompressible video/binary data.
        headers["Accept-Encoding"] = "identity"

        return headers
    }
}
