package com.elewashy.nexa.feature.downloads.data.engine

import android.os.Looper
import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Main-thread command ownership; each permit covers the entire task and its cleanup. */
class DownloadEngine(
    maxConcurrentDownloads: Int = 3,
    private val onProgress: (DownloadItem) -> Unit = {},
    private val onStatusChange: (DownloadItem, DownloadStatus) -> Unit = { _, _ -> },
    private val onMetadata: suspend (DownloadItem, String?) -> Unit = { _, _ -> },
) {
    private val clientDelegate = lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
            .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
            .addNetworkInterceptor(DownloadHttp.redirectCredentials)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
    private val client by clientDelegate
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val limiter = AdjustableConcurrencyLimiter(maxConcurrentDownloads)
    private val bandwidth = BandwidthLimiter()
    private val tasks = ConcurrentHashMap<Long, DownloadTask>()
    private val jobs = mutableMapOf<Long, Job>()

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "DownloadEngine command must run on main" }
    }

    fun enqueue(item: DownloadItem) {
        assertMainThread()
        if (tasks.containsKey(item.id)) return
        tasks[item.id] = createTask(item)
        run(item.id)
    }

    fun resume(id: Long) {
        assertMainThread()
        run(id)
    }

    private fun run(id: Long) {
        if (jobs[id]?.isActive == true) return
        val task = tasks[id] ?: return
        val previous = jobs[id]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            limiter.withPermit {
                if (tasks[id] !== task) return@withPermit
                task.resume(this@launch).join()
            }
        }
        jobs[id] = job
        job.start()
    }

    fun pause(id: Long) {
        assertMainThread()
        tasks[id]?.pause()
        jobs[id]?.cancel()
    }

    suspend fun cancel(id: Long) {
        assertMainThread()
        val cleanup = tasks.remove(id)?.cancel(scope)
        val previous = jobs.remove(id)
        previous?.cancelAndJoin()
        cleanup?.join()
    }

    suspend fun remove(id: Long) {
        assertMainThread()
        val cleanup = tasks.remove(id)?.cleanup()
        jobs.remove(id)?.cancelAndJoin()
        cleanup?.join()
    }

    fun updateMaxConcurrentDownloads(value: Int) {
        scope.launch { limiter.updateLimit(value) }
    }

    fun updateSpeedLimit(bytesPerSecond: Long) = bandwidth.updateLimit(bytesPerSecond)

    fun restoreTask(item: DownloadItem, restoredSegments: List<PersistedSegment> = emptyList()) {
        tasks.putIfAbsent(item.id, createTask(item, restoredSegments))
    }

    fun snapshotProgress(downloadId: Long, itemStatus: DownloadStatus): DownloadTask.ProgressSnapshot =
        tasks[downloadId]?.snapshotProgress(itemStatus in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.FAILED))
            ?: DownloadTask.ProgressSnapshot(0, -1, emptyList())

    fun dropRestoredTasks() {
        tasks.keys.toList().forEach { id -> if (jobs[id] == null) tasks.remove(id) }
    }

    fun hasTask(downloadId: Long) = tasks.containsKey(downloadId)

    fun close() {
        tasks.values.forEach { it.cleanup() }
        tasks.clear()
        jobs.clear()
        scope.cancel()
        if (clientDelegate.isInitialized()) {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun createTask(item: DownloadItem, restoredSegments: List<PersistedSegment> = emptyList()) =
        DownloadTask(item, client,
            onProgress = { onProgress(it.item) },
            onStatusChange = { task, status -> onStatusChange(task.item, status) },
            restoredSegments = restoredSegments, bandwidthLimiter = bandwidth, onMetadata = onMetadata)
}
