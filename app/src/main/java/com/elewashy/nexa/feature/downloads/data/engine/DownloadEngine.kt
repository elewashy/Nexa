package com.elewashy.nexa.feature.downloads.data.engine

import android.os.Looper
import android.util.Log
import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Central download manager that replaces the Fetch 2 library.
 *
 * Responsibilities:
 *  - Manages the lifecycle of all [DownloadTask] instances.
 *  - Limits concurrent downloads via a [Semaphore].
 *  - Provides a shared [OkHttpClient] optimized for large parallel downloads.
 *  - Routes pause/resume/cancel commands to individual tasks.
 *  - Reports progress and status changes to the [DownloadService] layer.
 *
 * Architecture:
 * ```
 *  DownloadEngine
 *   ├─ DownloadTask 1  (≤8 SegmentDownloaders)
 *   ├─ DownloadTask 2  (≤8 SegmentDownloaders)
 *   └─ DownloadTask 3  (≤8 SegmentDownloaders)
 *       ↕ Shared OkHttpClient (connection pool, dispatcher)
 *       ↕ Shared CoroutineScope (cancelled on engine close)
 * ```
 *
 * Thread-safety:
 *  - [activeTasks] is a [ConcurrentHashMap] for lock-free reads.
 *  - The [downloadSemaphore] limits concurrent downloads (queued tasks wait).
 *  - Each [DownloadTask] uses its own [SupervisorJob] for segment isolation.
 *
 * Important design note:
 *  The [DownloadItem] instance passed to [enqueue]/[restoreTask] is shared
 *  between the engine and the caller (DownloadService). The engine's tasks
 *  mutate this instance directly for progress/status. This is intentional to
 *  avoid copy overhead on the hot progress path.
 *
 * @property maxConcurrentDownloads  Maximum number of files downloading simultaneously.
 * @property onProgress             Called when any download's progress changes.
 * @property onStatusChange         Called when any download's status changes.
 */
class DownloadEngine(
    maxConcurrentDownloads: Int = 3,
    private val onProgress: (DownloadItem) -> Unit = {},
    private val onStatusChange: (DownloadItem, DownloadStatus) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "DownloadEngine"
    }

    // ── Shared OkHttp client ────────────────────────────────────────────
    // Optimized for high-throughput parallel downloads:
    //  - Large connection pool (96 connections) for many concurrent segments
    //  - Generous timeouts for large files on unstable networks
    //  - Custom dispatcher with high max-requests to avoid queuing
    //
    // Intentionally NOT derived from HttpClientProvider: this client replaces
    // the dispatcher and connection pool (the exact resources a derived client
    // would share) and close() shuts them down, which would corrupt a shared
    // client. A dedicated instance keeps the tuning isolated.

    private val httpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            // Allow many concurrent requests (segments × concurrent downloads)
            maxRequests = 96
            maxRequestsPerHost = 96
        }

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(96, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // No call timeout — large files can take hours
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // Larger socket receive buffer for better TCP throughput
            .socketFactory(object : javax.net.SocketFactory() {
                private val defaultFactory = getDefault()
                override fun createSocket(): java.net.Socket {
                    return (defaultFactory.createSocket() as java.net.Socket).apply {
                        receiveBufferSize = 256 * 1024 // Optimized for 4GB RAM devices (approx. 256KB)
                    }
                }
                override fun createSocket(host: String, port: Int): java.net.Socket {
                    return (defaultFactory.createSocket(host, port) as java.net.Socket).apply {
                        receiveBufferSize = 128 * 1024
                    }
                }
                override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket {
                    return (defaultFactory.createSocket(host, port, localHost, localPort) as java.net.Socket).apply {
                        receiveBufferSize = 128 * 1024
                    }
                }
                override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket {
                    return (defaultFactory.createSocket(host, port) as java.net.Socket).apply {
                        receiveBufferSize = 128 * 1024
                    }
                }
                override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket {
                    return (defaultFactory.createSocket(host, port, localHost, localPort) as java.net.Socket).apply {
                        receiveBufferSize = 128 * 1024
                    }
                }
            })
            .build()
    }

    // ── Coroutine scope ──────────────────────────────────────────────────

    /** Engine scope — all tasks are children. Cancelled in [close]. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Adjustable file-level gate plus one aggregate limiter shared by every segment. */
    private val concurrencyLimiter = AdjustableConcurrencyLimiter(maxConcurrentDownloads)
    private val bandwidthLimiter = BandwidthLimiter()

    // ── Task registry ───────────────────────────────────────────────────

    /** Maps downloadId → active DownloadTask. */
    private val activeTasks = ConcurrentHashMap<Long, DownloadTask>()

    /** Maps downloadId → the Job that holds the semaphore permit. */
    private val taskJobs = ConcurrentHashMap<Long, Job>()

    /** Maps downloadId → CompletableDeferred signalled on terminal status. */
    private val taskCompletions = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    // ===================================================================
    //  Public API
    // ===================================================================

    /**
     * The task-registry mutators below are main-thread confined: the
     * check-then-put guards (e.g. [resume]'s in-flight check) are only race-free
     * because every caller serialises on the main thread. Enforce the contract
     * instead of relying on convention — a caller added later that skips the
     * repository layer would otherwise silently re-open the race.
     * [restoreTask] and [snapshotSegments] are exempt: they touch only
     * [ConcurrentHashMap]s and run during init/snapshot passes.
     */
    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadEngine mutator called off the main thread"
        }
    }

    /**
     * Enqueues and starts a new download.
     *
     * If the maximum concurrent downloads are already running, this download
     * will wait for a permit from the semaphore before starting.
     *
     * @param item         The [DownloadItem] describing the file to download.
     *                     This instance is shared — the engine mutates it directly.
     * @param initialProbe Optional probe result already fetched upstream
     *                     (filename resolution). Reused so the URL is probed once.
     */
    fun enqueue(item: DownloadItem, initialProbe: HttpProber.ProbeResult? = null) {
        assertMainThread()
        if (activeTasks.containsKey(item.id)) {
            Log.w(TAG, "Download ${item.id} already active, ignoring enqueue")
            return
        }

        Log.d(TAG, "Enqueueing: ${item.fileName} (id=${item.id})")

        val task = createTask(item, initialProbe = initialProbe)
        activeTasks[item.id] = task

        val completion = CompletableDeferred<Unit>()
        taskCompletions[item.id] = completion

        val job = engineScope.launch {
            try {
                concurrencyLimiter.withPermit {
                    if (!isActive || !activeTasks.containsKey(item.id)) return@withPermit
                    task.start(this@launch)
                    completion.await()
                }
            } finally {
                // Identity removal — a late finally from a superseded wrapper
                // (e.g. pause then immediate resume) must not evict the NEW
                // wrapper's completion deferred.
                taskCompletions.remove(item.id, completion)
            }
        }

        taskJobs[item.id] = job
    }

    /** Updates future concurrency without interrupting active files. */
    fun updateMaxConcurrentDownloads(value: Int) {
        engineScope.launch { concurrencyLimiter.updateLimit(value) }
    }

    /** Applies an aggregate byte-rate cap immediately; zero means unlimited. */
    fun updateSpeedLimit(bytesPerSecond: Long) {
        bandwidthLimiter.updateLimit(bytesPerSecond)
    }

    /**
     * Pauses an active download.
     */
    fun pause(downloadId: Long) {
        assertMainThread()
        val task = activeTasks[downloadId]
        if (task == null) {
            Log.w(TAG, "No active task for download $downloadId")
            return
        }

        task.pause()

        // Cancel the wrapper job to release the semaphore permit
        taskJobs[downloadId]?.cancel()
        taskJobs.remove(downloadId)
    }

    /**
     * Resumes a paused or failed download.
     */
    fun resume(downloadId: Long) {
        assertMainThread()
        val task = activeTasks[downloadId]
        if (task == null) {
            Log.w(TAG, "No task to resume for download $downloadId")
            return
        }

        // A resume may already be in flight (double-tap, or an automatic
        // retry racing a manual resume). Without this guard a second wrapper
        // job replaces the completion deferred the first job awaits — leaking
        // its semaphore permit forever — and the task would run its segment
        // loop twice in parallel.
        if (taskJobs[downloadId]?.isActive == true) {
            Log.w(TAG, "Resume already in flight for download $downloadId")
            return
        }

        Log.d(TAG, "Resuming download $downloadId")

        val completion = CompletableDeferred<Unit>()
        taskCompletions[downloadId] = completion

        val job = engineScope.launch {
            try {
                concurrencyLimiter.withPermit {
                    if (!isActive || !activeTasks.containsKey(downloadId)) return@withPermit
                    task.resume(this@launch)
                    completion.await()
                }
            } finally {
                // Identity removal — see enqueue().
                taskCompletions.remove(downloadId, completion)
            }
        }

        taskJobs[downloadId] = job
    }

    /**
     * Cancels a download and deletes the partial file.
     *
     * IMPORTANT: call `task.cancel()` BEFORE cancelling the wrapper job.
     * If we cancel the wrapper first, segment coroutines are cancelled → they
     * set segment.status = PAUSED → may trigger a PAUSED callback before the
     * definitive CANCELLED status. Cancelling the task first ensures CANCELLED
     * is the first (and only) status that reaches the UI.
     */
    fun cancel(downloadId: Long) {
        assertMainThread()
        val task = activeTasks.remove(downloadId)

        // First: set the definitive CANCELLED status (fires callback immediately)
        task?.cancel(cleanupScope = engineScope)

        // Then: cancel the wrapper coroutine (releases semaphore permit)
        taskJobs[downloadId]?.cancel()
        taskJobs.remove(downloadId)
        taskCompletions[downloadId]?.complete(Unit)
        taskCompletions.remove(downloadId)

        Log.d(TAG, "Download $downloadId cancelled")
    }

    /**
     * Removes a download from tracking without deleting the file.
     * Used for completed downloads or "remove from list" actions.
     */
    fun remove(downloadId: Long) {
        assertMainThread()
        val task = activeTasks.remove(downloadId)
        taskJobs[downloadId]?.cancel()
        taskJobs.remove(downloadId)

        // Only cleanup (close file handles) — don't delete the file
        task?.cleanup(cleanupScope = engineScope)
    }

    /**
     * Re-creates a task for resuming a download that was restored from persistence
     * (e.g., after service restart). The task won't start until [resume] is called.
     *
     * @param restoredSegments Persisted segment state from the previous process,
     *                         verified on-disk before the task resumes from it.
     */
    fun restoreTask(item: DownloadItem, restoredSegments: List<PersistedSegment> = emptyList()) {
        if (activeTasks.containsKey(item.id)) return

        val task = createTask(item, restoredSegments = restoredSegments)
        activeTasks[item.id] = task
        Log.d(TAG, "Restored task: ${item.fileName} (id=${item.id}, " +
                "segments=${restoredSegments.size})")
    }

    /**
     * Atomic progress snapshot (bytes + total + segment rows) for persistence.
     * Empty segments when the task is unknown or has no bounded segments yet.
     *
     * [itemStatus] gates the restored-state fallback inside the task: only
     * still-resumable items (DOWNLOADING/PAUSED/FAILED — FAILED is retryable)
     * may fall back to their persisted segments; a fresh PENDING task has no
     * resume state and must not resurrect stale segments.
     */
    fun snapshotProgress(
        downloadId: Long,
        itemStatus: DownloadStatus,
    ): DownloadTask.ProgressSnapshot {
        val task = activeTasks[downloadId]
            ?: return DownloadTask.ProgressSnapshot(0L, -1L, emptyList())
        val resumable = itemStatus == DownloadStatus.DOWNLOADING ||
            itemStatus == DownloadStatus.PAUSED ||
            itemStatus == DownloadStatus.FAILED
        return task.snapshotProgress(allowRestoredFallback = resumable)
    }

    /**
     * Drops tasks restored by an aborted initialisation attempt (no live job),
     * so a retry cannot stay bound to orphaned DownloadItem instances.
     */
    fun dropRestoredTasks() {
        activeTasks.keys.toList().forEach { id ->
            if (taskJobs[id] == null) activeTasks.remove(id)
        }
    }

    /**
     * Closes the engine: cancels all downloads and releases all resources.
     */
    fun close() {
        Log.d(TAG, "Closing download engine")

        // Cleanup must run on a scope that outlives engineScope — launching
        // writer closes on engineScope and then cancelling it would leak
        // every file descriptor.
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Cancel all running tasks
        activeTasks.values.forEach { it.cleanup(cleanupScope = cleanupScope) }
        activeTasks.clear()
        taskJobs.clear()
        taskCompletions.values.forEach { it.complete(Unit) }
        taskCompletions.clear()

        // Cancel the engine scope
        engineScope.cancel()

        // Shut down the OkHttp dispatcher
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    /**
     * Returns true if a download with this ID is currently tracked.
     */
    fun hasTask(downloadId: Long): Boolean = activeTasks.containsKey(downloadId)

    // ===================================================================
    //  Internal
    // ===================================================================

    /**
     * Creates a [DownloadTask] wired to this engine's callbacks.
     */
    private fun createTask(
        item: DownloadItem,
        initialProbe: HttpProber.ProbeResult? = null,
        restoredSegments: List<PersistedSegment> = emptyList()
    ): DownloadTask {
        return DownloadTask(
            item = item,
            client = httpClient,
            onProgress = { task ->
                onProgress(task.item)
            },
            onStatusChange = { task, status ->
                onStatusChange(task.item, status)

                // Signal the CompletableDeferred on terminal states to release
                // the semaphore permit immediately (no polling delay)
                if (status == DownloadStatus.COMPLETED ||
                    status == DownloadStatus.CANCELLED ||
                    status == DownloadStatus.FAILED ||
                    status == DownloadStatus.PAUSED
                ) {
                    taskCompletions[task.item.id]?.complete(Unit)

                    if (status == DownloadStatus.CANCELLED) {
                        activeTasks.remove(task.item.id)
                    }
                }
            },
            initialProbe = initialProbe,
            restoredSegments = restoredSegments,
            bandwidthLimiter = bandwidthLimiter,
        )
    }

}
