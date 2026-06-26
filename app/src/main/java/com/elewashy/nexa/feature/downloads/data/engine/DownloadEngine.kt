package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
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
    private val maxConcurrentDownloads: Int = 3,
    private val onProgress: (DownloadItem) -> Unit = {},
    private val onStatusChange: (DownloadItem, DownloadStatus) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "DownloadEngine"
    }

    // ── Shared OkHttp client ────────────────────────────────────────────
    // Optimized for high-throughput parallel downloads:
    //  - Large connection pool (32 connections) for many concurrent segments
    //  - Generous timeouts for large files on unstable networks
    //  - Custom dispatcher with high max-requests to avoid queuing

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

    /** Semaphore that limits how many downloads can run concurrently. */
    private val downloadSemaphore = Semaphore(maxConcurrentDownloads)

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
     * Enqueues and starts a new download.
     *
     * If the maximum concurrent downloads are already running, this download
     * will wait for a permit from the semaphore before starting.
     *
     * @param item  The [DownloadItem] describing the file to download.
     *              This instance is shared — the engine mutates it directly.
     */
    fun enqueue(item: DownloadItem) {
        if (activeTasks.containsKey(item.id)) {
            Log.w(TAG, "Download ${item.id} already active, ignoring enqueue")
            return
        }

        Log.d(TAG, "Enqueueing: ${item.fileName} (id=${item.id})")

        val task = createTask(item)
        activeTasks[item.id] = task

        val completion = CompletableDeferred<Unit>()
        taskCompletions[item.id] = completion

        // Launch a wrapper coroutine that acquires a semaphore permit
        val job = engineScope.launch {
            var acquired = false
            try {
                // Wait for a download slot
                downloadSemaphore.acquire()
                acquired = true

                // Check if the task was cancelled while waiting
                if (!isActive || !activeTasks.containsKey(item.id)) {
                    return@launch
                }

                // Start the actual download
                task.start(this)

                // Suspend until onStatusChange signals a terminal state (no polling)
                completion.await()
            } finally {
                if (acquired) downloadSemaphore.release()
                taskCompletions.remove(item.id)
            }
        }

        taskJobs[item.id] = job
    }

    /**
     * Pauses an active download.
     */
    fun pause(downloadId: Long) {
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
        val task = activeTasks[downloadId]
        if (task == null) {
            Log.w(TAG, "No task to resume for download $downloadId")
            return
        }

        Log.d(TAG, "Resuming download $downloadId")

        val completion = CompletableDeferred<Unit>()
        taskCompletions[downloadId] = completion

        // Re-enqueue with semaphore
        val job = engineScope.launch {
            var acquired = false
            try {
                downloadSemaphore.acquire()
                acquired = true

                if (!isActive || !activeTasks.containsKey(downloadId)) {
                    return@launch
                }

                task.resume(this)
                completion.await()
            } finally {
                if (acquired) downloadSemaphore.release()
                taskCompletions.remove(downloadId)
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
        val task = activeTasks.remove(downloadId)
        taskJobs[downloadId]?.cancel()
        taskJobs.remove(downloadId)

        // Only cleanup (close file handles) — don't delete the file
        task?.cleanup(cleanupScope = engineScope)
    }

    /**
     * Re-creates a task for resuming a download that was restored from persistence
     * (e.g., after service restart). The task won't start until [resume] is called.
     */
    fun restoreTask(item: DownloadItem) {
        if (activeTasks.containsKey(item.id)) return

        val task = createTask(item)
        activeTasks[item.id] = task
        Log.d(TAG, "Restored task: ${item.fileName} (id=${item.id})")
    }

    /**
     * Closes the engine: cancels all downloads and releases all resources.
     */
    fun close() {
        Log.d(TAG, "Closing download engine")

        // Cancel all running tasks
        activeTasks.values.forEach { it.cleanup(cleanupScope = engineScope) }
        activeTasks.clear()
        taskJobs.clear()
        taskCompletions.values.forEach { it.complete(Unit) }
        taskCompletions.clear()

        // Cancel the engine scope (this will also cancel cleanup coroutines)
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
    private fun createTask(item: DownloadItem): DownloadTask {
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
            }
        )
    }

}
