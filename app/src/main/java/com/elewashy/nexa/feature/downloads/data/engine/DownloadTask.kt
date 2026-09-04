package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
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
    private val onStatusChange: (DownloadTask, DownloadStatus) -> Unit = { _, _ -> },
    /** Probe result fetched upstream (filename resolution) — reused instead of probing again. */
    private var initialProbe: HttpProber.ProbeResult? = null,
    /** Segment state persisted before the last process death, if any. */
    private val restoredSegments: List<PersistedSegment> = emptyList(),
    private val bandwidthLimiter: BandwidthLimiter = BandwidthLimiter(),
) {
    companion object {
        private const val TAG = "DownloadTask"

        /**
         * Locale-independent failure sentinels stored in
         * [DownloadItem.errorMessage]. The task has no Context, so these are
         * resolved to localized strings at the single display site
         * (DownloadNotificationManager).
         */
        const val ERROR_FINALIZE_FAILED = "ERR_DOWNLOAD_FINALIZE_FAILED"
        const val ERROR_FILE_MISSING = "ERR_DOWNLOADED_FILE_MISSING"

        /**
         * Resume was attempted but the server could not be reached at all
         * (timeout/DNS/connection). Progress is intact — retry later, never
         * restart.
         */
        const val ERROR_RESUME_UNREACHABLE = "ERR_RESUME_SERVER_UNREACHABLE"

        /** The server answered with a client error (expired/dead link). */
        const val ERROR_RESUME_URL_DEAD = "ERR_RESUME_URL_DEAD"

        /** Storage write permission is missing (set by the repository layer). */
        const val ERROR_STORAGE_PERMISSION = "ERR_STORAGE_PERMISSION_MISSING"

        /** Minimum interval between progress updates to avoid UI flooding. */
        private const val PROGRESS_THROTTLE_MS = 250L

        /** In-progress data lives in a `.part` file and is renamed on completion. */
        const val PART_SUFFIX = ".part"

        /**
         * Pure core of the persisted-segment restore: verifies that [restoredSegments]
         * form contiguous, non-overlapping coverage of `[0, totalBytes)` with sane
         * per-segment progress, then maps them to fresh [DownloadSegment]s.
         * Returns null when the state is absent or inconsistent (caller must
         * restart cleanly). Unit-testable without a live task.
         */
        internal fun rebuildSegmentsFromState(
            restoredSegments: List<PersistedSegment>,
            totalBytes: Long
        ): List<DownloadSegment>? {
            if (restoredSegments.isEmpty() || totalBytes <= 0) return null

            val sorted = restoredSegments.sortedBy { it.startByte }
            var expectedStart = 0L
            for (state in sorted) {
                if (state.endByte == Long.MAX_VALUE) return null
                val length = state.endByte - state.startByte + 1
                // Contiguous, non-overlapping coverage of [0, totalBytes)
                if (state.startByte != expectedStart || length <= 0) return null
                if (state.endByte >= totalBytes) return null
                if (state.downloadedBytes < 0 || state.downloadedBytes > length) return null
                expectedStart = state.endByte + 1
            }
            if (expectedStart != totalBytes) return null

            return sorted.mapIndexed { index, state ->
                val length = state.endByte - state.startByte + 1
                val done = state.completed || state.downloadedBytes >= length
                DownloadSegment(
                    id = index,
                    startByte = state.startByte,
                    endByte = state.endByte,
                    downloadedBytes = if (done) length else state.downloadedBytes,
                    status = if (done) SegmentStatus.COMPLETED else SegmentStatus.PENDING
                )
            }
        }
    }

    /** Partial file path — the public filename is only touched on completion. */
    private val partFilePath: String get() = item.filePath + PART_SUFFIX

    // ── State ───────────────────────────────────────────────────────────

    /** Coroutine scope for this task — cancelled on cancel/pause. */
    @Volatile
    private var taskScope: CoroutineScope? = null

    /** File writer shared by all segments. */
    @Volatile private var fileWriter: SegmentFileWriter? = null

    /**
     * Live segment objects. Every read or mutation is serialized by
     * [segmentsLock]. The list itself is replaced, never mutated. This single
     * ownership boundary is required because dynamic splitting changes a
     * segment's end while a downloader is selecting its next write range.
     */
    private var segments: List<DownloadSegment> = emptyList()
    private val segmentsLock = Any()

    /**
     * Active [SegmentDownloader] instances.
     * Tracked so that [cancel]/[pause] can immediately cancel their OkHttp calls,
     * interrupting any blocking `stream.read()` without waiting for timeout.
     */
    private val activeDownloaders = mutableListOf<SegmentDownloader>()

    /** Tracks whether server supports Range requests. */
    @Volatile private var supportsRanges = false

    /** Set after a resume range re-probe fails — skip future re-probes. */
    private var rangeProbeConfirmedFailed = false

    /** Next segment ID for dynamic splitting. */
    private var nextSegmentId = 0

    /**
     * Bytes already downloaded in a previous session (before app restart).
     * Set by [resumeAfterRestart] so that progress aggregation correctly
     * adds pre-existing bytes to the segment's session-only count.
     */
    @Volatile private var baseDownloadedBytes = 0L

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

    /** Set by [cancel]; finalization checks it so a racing cancel cannot orphan files. */
    @Volatile private var cancelled = false

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
        cancelled = false
        val scope = CoroutineScope(
            SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO
        )
        taskScope = scope

        updateStatus(DownloadStatus.PENDING)

        scope.launch {
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
                releaseWriter()
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
        // Persistent segment metadata survives; live file descriptors do not.
        releaseWriter()

        synchronized(segmentsLock) {
            segments.forEach { seg ->
                if (seg.status == SegmentStatus.DOWNLOADING || seg.status == SegmentStatus.PENDING) {
                    seg.status = SegmentStatus.PAUSED
                }
            }
        }

        item.downloadSpeedBytesPerSecond = 0
        updateStatus(DownloadStatus.PAUSED)
    }

    /**
     * Resumes the download from where it left off.
     * Only segments with remaining bytes are restarted.
     *
     * If segments are empty (e.g., after service restart + restore from
     * persistence), [resumeAfterRestart] verifies the persisted segment state
     * against the on-disk .part file before resuming; anything inconsistent
     * triggers a clean restart.
     */
    fun resume(parentScope: CoroutineScope) {
        cancelled = false
        val resumeState = synchronized(segmentsLock) {
            ResumeState(segments.isNotEmpty(), segments.any { it.hasRemainingBytes })
        }
        Log.d(TAG, "Resuming: ${item.fileName} " +
                "(segments=${if (resumeState.hasSegments) "present" else "empty"}, downloaded=${item.downloadedBytes})")

        // Reset speed counters to avoid stale speed readings after resume
        resetSpeedCounters()

        // Save downloaded bytes before any reset — needed for range re-probe
        val pausedAtBytes = item.downloadedBytes

        val scope = CoroutineScope(
            SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO
        )
        taskScope = scope

        updateStatus(DownloadStatus.DOWNLOADING)

        scope.launch {
            try {
                when {
                    // Case 1: We have segments with remaining bytes (normal pause/resume)
                    resumeState.hasSegments && resumeState.hasRemaining -> {
                        resumeSegments(pausedAtBytes)
                    }
                    // Case 2: No segments but partial file exists (app restart with progress)
                    !resumeState.hasSegments && item.downloadedBytes > 0 && item.totalBytes > 0 -> {
                        Log.d(TAG, "Resuming after app restart: ${item.fileName} " +
                                "(${item.downloadedBytes}/${item.totalBytes} bytes)")
                        resumeAfterRestart()
                    }
                    // Case 3: All segments complete, zero bytes left (e.g. a failed
                    // finalization rename). Re-run finalization — a full re-download
                    // would waste every byte already on disk.
                    resumeState.hasSegments && !resumeState.hasRemaining -> {
                        Log.d(TAG, "All segments complete, retrying finalization: ${item.fileName}")
                        handleAllSegmentsCompleted()
                    }
                    // Case 4: No segments, no progress — full fresh download
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
            } catch (e: RangeNotSupportedException) {
                // Server stopped honoring ranges mid-lifecycle. Same recovery
                // as resumeAfterRestart: discard the ranged plan and re-probe
                // from scratch (executeDownload falls back to single-stream).
                Log.w(TAG, "Ranges no longer supported on resume — restarting cleanly: ${item.fileName}")
                supportsRanges = false
                restartCleanly()
            } catch (e: Exception) {
                Log.e(TAG, "Resume failed: ${item.fileName} — ${e.message}", e)
                releaseWriter()
                updateStatus(DownloadStatus.FAILED)
            }
        }
    }

    /**
     * Cancels the download permanently and deletes the partial file.
     *
     * File close/delete runs on the cleanup scope's IO dispatcher — never on
     * the calling (main) thread, which would jank the UI on large files.
     */
    fun cancel(cleanupScope: CoroutineScope? = null) {
        Log.d(TAG, "Cancelling: ${item.fileName}")

        // Flag first — a completion rename racing this cancel must see it and
        // delete the just-renamed final file instead of orphaning it.
        cancelled = true

        // Cancel active OkHttp calls FIRST — this interrupts blocking stream.read()
        // so segments stop within milliseconds instead of waiting for timeout.
        cancelAllActiveCalls()

        // Now cancel the coroutine scope
        taskScope?.cancel()
        taskScope = null

        val writer = fileWriter
        fileWriter = null
        val scope = cleanupScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch(Dispatchers.IO) {
            writer?.deleteFile()
        }

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

    /**
     * Closes and drops the file writer at terminal failure so a permanently
     * FAILED task holds no file descriptor. [resumeSegments] recreates the
     * writer on the next resume; finalization never needs it.
     */
    private fun releaseWriter() {
        val writer = fileWriter
        fileWriter = null
        writer?.close()
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
        item.errorMessage = null

        // A previous attempt (e.g. FAILED with a live writer) must not leak
        // its file descriptor into the fresh plan.
        releaseWriter()

        // Build header map from DownloadItem metadata
        val headers = buildHeaderMap()

        // ── Step 1: Probe the server ─────────────────────────────────────
        // Reuse the upstream probe (filename resolution) when available so the
        // URL is only hit once — GET fallbacks can burn one-time signed URLs.
        val probeResult = initialProbe ?: run {
            Log.d(TAG, "Probing: ${item.url}")
            HttpProber.probe(client, item.url, headers)
        }
        initialProbe = null

        // Definitive client error from the probe — the URL is dead. Fail fast
        // instead of burning segment retries (and auto-retry cycles) on it.
        if (probeResult.reachable &&
            probeResult.statusCode in 400..499 &&
            probeResult.statusCode != 408 && probeResult.statusCode != 429
        ) {
            Log.w(TAG, "Probe got HTTP ${probeResult.statusCode} — URL is dead: ${item.fileName}")
            item.errorMessage = ERROR_RESUME_URL_DEAD
            updateStatus(DownloadStatus.FAILED)
            return
        }

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

        synchronized(segmentsLock) {
            segments = initialSegments
            nextSegmentId = segments.size
        }

        // ── Step 3: Pre-allocate the .part file ──────────────────────────
        // Fresh start — never build on stale bytes (unknown-size downloads get
        // no pre-allocation truncation, so leftovers would survive the rename).
        File(partFilePath).delete()
        val writer = SegmentFileWriter(partFilePath, totalSize)
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
            synchronized(segmentsLock) {
                segments = SegmentPlan.createSingleSegment(item.totalBytes)
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
     * Resume is only attempted when the persisted per-segment state is present
     * AND the on-disk .part file is consistent with it — segments complete out
     * of order, so assuming [0..downloadedBytes] is contiguous would resume
     * into zero-filled holes. Any missing/inconsistent state falls back to a
     * clean restart.
     */
    private suspend fun resumeAfterRestart() {
        val headers = buildHeaderMap()

        // Re-probe the server to verify range support and current file size
        Log.d(TAG, "Re-probing for resume: ${item.url}")
        val probeResult = HttpProber.probe(client, item.url, headers)

        if (!probeResult.reachable) {
            // No response at all (timeout, DNS, connection failure). That is
            // UNKNOWN, not "no ranges" — restarting would throw away verified
            // progress based on a transient network hiccup. Fail retryable;
            // the .part file and persisted segments stay intact.
            Log.w(TAG, "Resume probe unreachable — keeping progress, failing retryable: ${item.fileName}")
            item.errorMessage = ERROR_RESUME_UNREACHABLE
            updateStatus(DownloadStatus.FAILED)
            return
        }

        if (probeResult.statusCode in 400..499 &&
            probeResult.statusCode != 408 && probeResult.statusCode != 429
        ) {
            // Definitive client error (403/404/410...) — the link is dead.
            // Re-downloading from zero would just fail (or fetch an error
            // page); surface it instead.
            Log.w(TAG, "Resume probe got HTTP ${probeResult.statusCode} — URL is dead: ${item.fileName}")
            item.errorMessage = ERROR_RESUME_URL_DEAD
            updateStatus(DownloadStatus.FAILED)
            return
        }

        if (probeResult.statusCode !in 200..299) {
            // Reachable but refused (5xx, 408, 429...) — transient. Keep the
            // verified progress and fail retryable; restarting would discard
            // it for a server that is merely overloaded right now.
            Log.w(TAG, "Resume probe got HTTP ${probeResult.statusCode} — " +
                    "transient refusal, failing retryable: ${item.fileName}")
            item.errorMessage = ERROR_RESUME_UNREACHABLE
            updateStatus(DownloadStatus.FAILED)
            return
        }

        supportsRanges = probeResult.supportsRanges

        // If server reports a size, validate it matches what we had
        if (probeResult.contentLength > 0) {
            if (item.totalBytes > 0 && probeResult.contentLength != item.totalBytes) {
                Log.w(TAG, "File size changed on server " +
                        "(was ${item.totalBytes}, now ${probeResult.contentLength}). " +
                        "Starting fresh.")
                restartCleanly()
                return
            }
            item.totalBytes = probeResult.contentLength
        }

        if (!supportsRanges) {
            // Server doesn't support ranges — must restart from scratch
            Log.w(TAG, "Server doesn't support ranges. Starting fresh: ${item.fileName}")
            restartCleanly()
            return
        }

        // Verify the partial (.part) file still exists on disk
        val partFile = File(partFilePath)
        if (!partFile.exists()) {
            Log.w(TAG, "Partial file missing. Starting fresh: ${item.fileName}")
            restartCleanly()
            return
        }

        val rebuilt = rebuildSegmentsFromPersistedState(partFile)
        if (rebuilt == null) {
            Log.w(TAG, "Persisted segment state missing or inconsistent — " +
                    "restarting cleanly: ${item.fileName}")
            restartCleanly()
            return
        }

        // Segments carry their own progress; no base-byte offset needed
        baseDownloadedBytes = 0
        item.downloadedBytes = rebuilt.sumOf { it.effectiveDownloadedBytes }
        item.errorMessage = null

        synchronized(segmentsLock) {
            segments = rebuilt
            nextSegmentId = rebuilt.size
        }

        // Open file writer without pre-allocation (file already exists)
        val writer = SegmentFileWriter(partFilePath, item.totalBytes)
        fileWriter = writer

        val remainingBytes = rebuilt.sumOf { it.remainingBytes }
        Log.d(TAG, "Resuming from verified segment state: ${item.fileName} " +
                "(${item.downloadedBytes}/${item.totalBytes} bytes, " +
                "${remainingBytes / 1024}KB remaining, ${rebuilt.size} segments)")

        lastSpeedCalcTime = System.currentTimeMillis()

        try {
            downloadAllSegments(writer, headers)
        } catch (e: RangeNotSupportedException) {
            Log.w(TAG, "Server doesn't actually support ranges on restart-resume, falling back: ${item.fileName}")
            supportsRanges = false
            restartCleanly()
        }
    }

    /**
     * Rebuilds in-memory segments from the persisted state, verifying that the
     * .part file can actually back them. Returns null when the state is absent
     * or inconsistent (caller must restart cleanly).
     */
    private fun rebuildSegmentsFromPersistedState(partFile: File): List<DownloadSegment>? {
        if (restoredSegments.isEmpty() || item.totalBytes <= 0) return null

        // The pre-allocated .part file must still cover every persisted byte range
        if (partFile.length() < item.totalBytes) {
            Log.w(TAG, "Part file too short (${partFile.length()} < ${item.totalBytes})")
            return null
        }
        return rebuildSegmentsFromState(restoredSegments, item.totalBytes)
    }

    /**
     * Discards unverifiable progress and starts over. Deletes the stale .part
     * file so pre-allocation never builds on top of zero-holes.
     */
    private suspend fun restartCleanly() {
        item.downloadedBytes = 0
        baseDownloadedBytes = 0
        synchronized(segmentsLock) { segments = emptyList() }
        File(partFilePath).delete()
        executeDownload()
    }

    /**
     * Point-in-time snapshot of segment state for persistence. Segments are
     * captured individually because they complete out of order — a plain
     * downloadedBytes counter cannot describe which ranges are actually on disk.
     *
     * Contract:
     *  - Non-blocking: runs on the main thread during service detach, so lock
     *    contention falls back to [lastGoodSegmentSnapshot] instead of waiting.
     *  - Restored fallback: in-memory segments are EMPTY for a task restored
     *    from persistence but not yet resumed. Persisting that empty list would
     *    wipe the resume state, so fall back to the restored segments (they are
     *    only used when they cover the current file size). Gated by
     *    [allowRestoredFallback] — the engine only enables it for items that
     *    are still resumable (DOWNLOADING/PAUSED).
     */
    fun snapshotProgress(allowRestoredFallback: Boolean = true): ProgressSnapshot {
        if (item.totalBytes <= 0) {
            return ProgressSnapshot(item.downloadedBytes, item.totalBytes, emptyList())
        }

        return synchronized(segmentsLock) {
            val current = segments.map { seg ->
                PersistedSegment(
                    startByte = seg.startByte,
                    endByte = seg.endByte,
                    downloadedBytes = seg.effectiveDownloadedBytes,
                    completed = seg.status == SegmentStatus.COMPLETED
                )
            }
            // Recompute from the SAME segment snapshot so the persisted bytes
            // can never disagree with the persisted rows.
            val bytes = baseDownloadedBytes + current.sumOf { it.downloadedBytes }
            val snapshot = when {
                current.isNotEmpty() -> ProgressSnapshot(bytes, item.totalBytes, current)
                allowRestoredFallback &&
                    restoredSegments.isNotEmpty() &&
                    restoredSegments.all { it.endByte < item.totalBytes } ->
                    ProgressSnapshot(
                        baseDownloadedBytes + restoredSegments.sumOf { it.downloadedBytes },
                        item.totalBytes,
                        restoredSegments,
                    )
                else -> ProgressSnapshot(bytes, item.totalBytes, current)
            }
            snapshot
        }
    }

    /** Resume decision captured atomically with respect to segment mutations. */
    private data class ResumeState(val hasSegments: Boolean, val hasRemaining: Boolean)

    /** Atomic persistence unit: progress bytes + total + segment rows. */
    data class ProgressSnapshot(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val segments: List<PersistedSegment>,
    )

    /**
     * Resumes downloading segments that still have remaining bytes.
     */
    private suspend fun resumeSegments(pausedAtBytes: Long = 0) {
        val headers = buildHeaderMap()
        val writer = fileWriter ?: run {
            // Re-create file writer if it was closed
            val w = SegmentFileWriter(partFilePath, item.totalBytes)
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

                synchronized(segmentsLock) {
                    segments = SegmentPlan.createSegmentsForRange(
                        pausedAtBytes, item.totalBytes - 1
                    )
                    nextSegmentId = segments.size
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

        synchronized(segmentsLock) {
            segments.forEach { seg ->
                if (seg.status == SegmentStatus.PAUSED || seg.status == SegmentStatus.FAILED) {
                    seg.retryCount = 0
                    seg.status = SegmentStatus.PENDING
                }
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
            call.awaitResponse().use { resp ->
                Log.d(TAG, "Resume range probe: GET bytes=$offset-$offset → ${resp.code}")
                resp.code == 206
            }
        } catch (e: CancellationException) {
            throw e
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
        val segmentsToDownload = synchronized(segmentsLock) {
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
        val finishedStatuses = synchronized(segmentsLock) { segments.map { it.status } }
        val allCompleted = finishedStatuses.all { it == SegmentStatus.COMPLETED }

        if (allCompleted) {
            handleAllSegmentsCompleted()
        } else {
            // Check if any segment was paused due to network loss
            val anyNetworkPaused = finishedStatuses.any { it == SegmentStatus.PAUSED }

            if (anyNetworkPaused) {
                throw NetworkLostException("One or more segments lost network")
            }

            val anyFailed = finishedStatuses.any { it == SegmentStatus.FAILED }
            if (anyFailed) {
                // Report FAILED and let the repository's automatic-retry policy
                // (bounded backoff, non-retryable classification) decide what
                // happens next — the engine no longer second-guesses it.
                // Release the writer: retry/resume recreates it.
                releaseWriter()
                updateStatus(DownloadStatus.FAILED)
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
        // Generation token: pause() nulls taskScope and resume() assigns a new
        // one, so identity comparison tells a late-running coroutine whether a
        // newer generation has superseded it.
        val scopeAtLaunch = taskScope
        scope.launch {
            val downloader = SegmentDownloader(
                client = client,
                url = item.url,
                headers = headers,
                segment = segment,
                fileWriter = writer,
                supportsRange = supportsRanges,
                bandwidthLimiter = bandwidthLimiter,
                onProgress = { _, bytesWritten ->
                    handleSegmentProgress(bytesWritten)
                },
                isStale = { taskScope !== scopeAtLaunch },
                stateLock = segmentsLock,
            )

            synchronized(activeDownloaders) { activeDownloaders.add(downloader) }

            try {
                downloader.download()
            } finally {
                synchronized(activeDownloaders) { activeDownloaders.remove(downloader) }
            }

            // When a segment completes, try dynamic splitting.
            // Launch the new segment in the SAME scope so downloadAllSegments waits for it.
            val shouldSplit = synchronized(segmentsLock) {
                segment.status == SegmentStatus.COMPLETED && supportsRanges
            }
            if (shouldSplit) tryDynamicSplit(scope, writer, headers, scopeAtLaunch)
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
        headers: Map<String, String>,
        scopeAtLaunch: CoroutineScope?,
    ) {
        val newSeg: DownloadSegment

        synchronized(segmentsLock) {
            // A pause/resume superseded this generation while the segment was
            // finishing — never append into a foreign generation's list.
            if (taskScope !== scopeAtLaunch) return

            val activeSegments = segments.filter { it.status == SegmentStatus.DOWNLOADING }
            val result = SegmentPlan.trySplitLargestSegment(activeSegments, nextSegmentId)
                ?: return

            val (_, newSegment) = result

            segments = segments + newSegment
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

        val totalDownloaded = synchronized(segmentsLock) {
            baseDownloadedBytes + segments.sumOf { it.effectiveDownloadedBytes }
        }
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
        releaseWriter()

        val remainingCount = synchronized(segmentsLock) {
            segments.forEach { seg ->
                if (seg.status == SegmentStatus.DOWNLOADING || seg.status == SegmentStatus.PENDING) {
                    seg.status = SegmentStatus.PAUSED
                }
            }
            segments.count { it.hasRemainingBytes }
        }

        // Set flag so DownloadService can auto-resume when network returns
        item.wasWaitingForNetwork = true
        item.downloadSpeedBytesPerSecond = 0

        Log.d(TAG, "Network lost — auto-pausing: ${item.fileName} " +
                "($remainingCount segments paused)")

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
        // A cancel can race the completion rename — bail before finalizing so
        // cancel's own cleanup owns the file state.
        if (cancelled) {
            Log.d(TAG, "Cancelled before finalization: ${item.fileName}")
            return
        }

        // Aggregate actual bytes from segments + base bytes from previous session.
        val actualDownloaded = synchronized(segmentsLock) {
            baseDownloadedBytes + segments.sumOf { it.effectiveDownloadedBytes }
        }

        if (item.totalBytes > 0) {
            item.downloadedBytes = item.totalBytes
        } else {
            // Unknown-size download — use the actual sum from segments
            item.downloadedBytes = actualDownloaded
            item.totalBytes = actualDownloaded  // Now we know the total
        }

        item.downloadSpeedBytesPerSecond = 0

        // Close the file writer
        fileWriter?.close()
        fileWriter = null

        if (!finalizeRenamedFile()) return

        updateStatus(DownloadStatus.COMPLETED)
        Log.d(TAG, "Download completed: ${item.fileName} " +
                "(${actualDownloaded / 1024}KB)")
    }

    /**
     * Promotes the finished `.part` file to its public name. Only after a
     * successful rename is the download considered complete, so the media
     * scanner and file pickers never observe a half-written file.
     *
     * Cancellation is checked before AND after the rename: if a cancel wins
     * the race while the rename is in flight, the just-renamed final file is
     * deleted so it is not orphaned (cancel's own cleanup only knows about the
     * `.part` path).
     */
    private fun finalizeRenamedFile(): Boolean {
        if (cancelled) {
            File(partFilePath).delete()
            return false
        }

        val partFile = File(partFilePath)
        val finalFile = File(item.filePath)
        if (partFile.exists()) {
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                Log.e(TAG, "Failed to rename part file to ${item.filePath}")
                item.errorMessage = ERROR_FINALIZE_FAILED
                updateStatus(DownloadStatus.FAILED)
                return false
            }
            if (cancelled) {
                // Cancel landed during the rename — remove the orphaned final file.
                finalFile.delete()
                File(partFilePath).delete()
                return false
            }
        } else if (!finalFile.exists()) {
            Log.e(TAG, "Download output missing at completion: ${item.filePath}")
            item.errorMessage = ERROR_FILE_MISSING
            updateStatus(DownloadStatus.FAILED)
            return false
        }
        return true
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
