package com.elewashy.nexa.feature.downloads.data

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import android.util.Log
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.feature.downloads.data.engine.DownloadEngine
import com.elewashy.nexa.feature.downloads.data.filename.FileNameResolver
import com.elewashy.nexa.feature.downloads.data.notification.DownloadNotificationManager
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadPersistence
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [DownloadRepository].
 *
 * Owns the full download stack:
 *  - [DownloadEngine]  (segmented parallel downloads)
 *  - [DownloadPersistence]  (SharedPreferences + Gson snapshot)
 *  - [DownloadNotificationManager]  (all user-facing notifications)
 *  - [ConcurrentHashMap] of live [DownloadItem]s (shared with the engine)
 *  - [ConnectivityManager.NetworkCallback]  (auto-resume on network return)
 *  - A 2 s periodic flush job on the application scope.
 *
 * Behavior is preserved byte-for-byte from the pre-Phase-3 `DownloadService`;
 * only the ownership of state has moved out of the service and up into this
 * @Singleton. The service now delegates intent routing here.
 *
 * Threading rules (unchanged from the old service):
 *  - Engine callbacks fire on IO; this class reposts them onto `Dispatchers.Main.immediate`
 *    before touching notifications, so all notification ops run on main.
 *  - State mutations go through [emit] which recomputes the sorted snapshot
 *    and pushes it to [_downloads]. Same frequency as the old broadcast.
 */
@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope
) : DownloadRepository {

    // ── State ──────────────────────────────────────────────────────────

    private val downloadItems = ConcurrentHashMap<Long, DownloadItem>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val downloadsState = _downloads.asStateFlow()
    override val downloads: StateFlow<List<DownloadItem>>
        get() {
            ensureInitialised()
            return downloadsState
        }

    // ── Delegates ──────────────────────────────────────────────────────

    /**
     * Notification manager — instantiated on first [attachService] and reused
     * thereafter. Holds a `Service` reference internally, so we rebuild it when
     * the hosting service changes (e.g. process restart).
     */
    private var notifManager: DownloadNotificationManager? = null

    private val persistence: DownloadPersistence by lazy { DownloadPersistence(context) }

    private val engine: DownloadEngine by lazy {
        DownloadEngine(
            maxConcurrentDownloads = 3,
            onProgress = { item ->
                appScope.launch(Dispatchers.Main.immediate) { updateProgress(item) }
            },
            onStatusChange = { item, status ->
                appScope.launch(Dispatchers.Main.immediate) { handleEngineStatusChange(item, status) }
            }
        ).also { Log.d(TAG, "Custom download engine initialised (segmented, parallel, OkHttp)") }
    }

    // ── Service binding (for foreground lifecycle only) ───────────────

    @Volatile
    private var attachedService: Service? = null

    // ── Background jobs ───────────────────────────────────────────────

    private var flushJob: Job? = null

    /**
     * Timestamp of last notification update — throttled to avoid Android's
     * notification rate limit (5/sec). Repository emissions still fire at full rate.
     */
    private var lastNotificationUpdateTime = 0L

    // ── Network monitoring ────────────────────────────────────────────

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── Initialisation ────────────────────────────────────────────────

    /**
     * Initialisation is idempotent and deferred to [attachService] so it runs
     * exactly once per process. Loads the persisted state, primes the engine
     * with restored tasks, and kicks off the periodic flush. The network
     * callback is service-scoped because auto-resume must have a foreground
     * service attached.
     */
    @Synchronized
    private fun ensureInitialised() {
        if (flushJob != null) return

        loadDownloadState()
        notifManager?.updateSummary(downloadItems.values)
        startPeriodicFlush()
        emit()
        Log.d(TAG, "DownloadRepository initialised")
    }

    // ===================================================================
    //  Service attach / detach
    // ===================================================================

    override fun attachService(service: Service) {
        attachedService = service

        // Instantiate or refresh the notification manager with the live service
        // reference (it needs Service.startForeground / stopForeground access).
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager = DownloadNotificationManager(service, nm).also { it.createChannel() }

        // Android 12+ requires startForeground() within 5 seconds of startService().
        // Show a minimal "preparing" notification immediately.
        notifManager?.startForegroundImmediately(service)
        ensureInitialised()
        registerNetworkCallback()
    }

    override fun detachService() {
        attachedService ?: return

        // Preserve pre-refactor behaviour: when the foreground-service host goes
        // away, stop any in-flight downloads. The engine instance is kept alive
        // (it's a singleton owned by this repository), so we pause each active
        // task instead of calling engine.close().
        val active = downloadItems.values.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
        }
        active.forEach { engine.pause(it.id) }

        // Clean up notifications: only cancel ones that are actively downloading
        // or pending; leave completed, failed, and paused so the user can still
        // interact with them or see them.
        active.forEach {
            notifManager?.cancelNotification(it.id)
            it.status = DownloadStatus.PAUSED // mark them paused internally so the summary matches
        }
        notifManager?.updateSummary(downloadItems.values)

        // Final flush to disk — snapshot items to avoid torn reads during serialization
        persistence.forceFlush(downloadItems.values.map { it.copy() })

        unregisterNetworkCallback()
        notifManager = null

        attachedService = null
        emit()
        Log.d(TAG, "DownloadRepository detached from service")
    }

    // ===================================================================
    //  Public commands
    // ===================================================================

    override suspend fun start(request: DownloadRequest) = withContext(Dispatchers.Main.immediate) {
        // Prevent duplicate active downloads for the same URL
        val alreadyActive = downloadItems.values.any {
            it.url == request.url && it.status in ACTIVE_STATUSES
        }
        if (alreadyActive) {
            Log.d(TAG, "Download already active for URL: ${request.url}")
            return@withContext
        }

        // Resolve filename from server (IO-bound, on the application scope so it
        // survives the caller's lifecycle — matches the old service behavior).
        appScope.launch {
            val (serverName, serverType) = try {
                FileNameResolver.fetchFilenameFromServer(
                    request.url, request.userAgent, request.referer, request.cookies
                )
            } catch (e: Exception) {
                Log.w(TAG, "Filename fetch failed, using fallback: ${e.message}")
                Pair(null, null)
            }

            val finalName = serverName ?: request.fileName
            val effectiveMime = if (serverType != null && request.forceExtension == null) {
                serverType
            } else {
                request.mimeType
            }

            Log.d(TAG, "Starting download: $finalName (MIME=$effectiveMime, forceExt=${request.forceExtension})")
            withContext(Dispatchers.Main.immediate) {
                createAndStartDownload(
                    url = request.url,
                    fileName = finalName,
                    mimeType = effectiveMime,
                    userAgent = request.userAgent,
                    referer = request.referer,
                    origin = request.origin,
                    cookies = request.cookies,
                    source = request.source,
                    forceExtension = request.forceExtension
                )
            }
        }
        Unit
    }

    override suspend fun pause(id: Long) = withContext(Dispatchers.Main.immediate) {
        downloadItems[id]?.let { item ->
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PENDING) {
                engine.pause(item.id)
                Log.d(TAG, "Pausing: ${item.fileName}")
            }
        }
        Unit
    }

    override suspend fun resume(id: Long) = withContext(Dispatchers.Main.immediate) {
        downloadItems[id]?.let { item ->
            if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
                item.failureCount = 0

                if (engine.hasTask(item.id)) {
                    engine.resume(item.id)
                    Log.d(TAG, "Resuming: ${item.fileName}")
                } else {
                    // Task not in engine (e.g. restored from persistence) — re-enqueue
                    Log.d(TAG, "Re-enqueueing: ${item.fileName}")
                    engine.restoreTask(item)
                    engine.resume(item.id)
                }
            }
        }
        Unit
    }

    override suspend fun retry(id: Long) = resume(id)

    override suspend fun cancel(id: Long) = cancelInternal(id, deleteFile = true)

    override suspend fun remove(id: Long) = cancelInternal(id, deleteFile = false)

    private suspend fun cancelInternal(id: Long, deleteFile: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            downloadItems[id]?.let { item ->
                val filePath = item.filePath // Capture before cleanup

                // 1) Set status to CANCELLED *first* so the notification system
                //    and summary see the correct state immediately.
                item.status = DownloadStatus.CANCELLED
                item.downloadSpeedBytesPerSecond = 0

                // 2) Remove from downloadItems BEFORE calling engine.cancel().
                //    This prevents the engine's inline CANCELLED callback
                //    (handleEngineStatusChange) from racing with this code.
                downloadItems.remove(item.id)

                // 3) Cancel the per-item notification and update the summary
                //    with the item already removed from the map.
                notifManager?.cancelNotification(item.id)
                notifManager?.updateSummary(downloadItems.values)

                // 4) Now tell the engine to stop and clean up files.
                //    The engine's CANCELLED callback will short-circuit because
                //    the item is no longer in downloadItems.
                if (deleteFile) {
                    engine.cancel(item.id)
                    Log.d(TAG, "Cancelling + deleting: ${item.fileName}")
                    deleteDownloadedFile(filePath)
                } else {
                    engine.remove(item.id)
                    Log.d(TAG, "Removing from list: ${item.fileName}")
                }

                persistence.markDirty()
                emit()
            }
            Unit
        }

    override fun observe(id: Long): Flow<DownloadItem?> =
        _downloads.map { list -> list.firstOrNull { it.id == id } }

    // ===================================================================
    //  Engine status callback
    // ===================================================================

    private fun handleEngineStatusChange(item: DownloadItem, newStatus: DownloadStatus) {
        // item IS the same instance as downloadItems[item.id] — no sync needed
        if (!downloadItems.containsKey(item.id)) return

        when (newStatus) {
            DownloadStatus.PENDING -> {
                updateStatus(item, DownloadStatus.PENDING)
            }
            DownloadStatus.DOWNLOADING -> {
                item.wasWaitingForNetwork = false
                updateStatus(item, DownloadStatus.DOWNLOADING)
            }
            DownloadStatus.PAUSED -> {
                item.downloadSpeedBytesPerSecond = 0
                updateStatus(item, DownloadStatus.PAUSED)

                // Show appropriate notification based on context
                when {
                    item.wasWaitingForNetwork -> {
                        notifManager?.showNetworkWaitNotification(item)
                    }
                    item.failureCount >= 2 -> {
                        notifManager?.showFailurePauseNotification(item)
                    }
                }
            }
            DownloadStatus.COMPLETED -> {
                item.downloadSpeedBytesPerSecond = 0
                item.failureCount = 0
                updateStatus(item, DownloadStatus.COMPLETED)
                scanMediaFile(item.filePath, item.mimeType)
            }
            DownloadStatus.FAILED -> {
                updateStatus(item, DownloadStatus.FAILED)
            }
            DownloadStatus.CANCELLED -> {
                // CANCELLED is normally handled by cancelInternal().
                // This path only fires if the engine cancels internally
                // (e.g., a cancel was requested while still in the queue).
                if (downloadItems.containsKey(item.id)) {
                    item.status = DownloadStatus.CANCELLED
                    downloadItems.remove(item.id)
                    notifManager?.cancelNotification(item.id)
                    notifManager?.updateSummary(downloadItems.values)
                    persistence.markDirty()
                    emit()
                }
            }
        }
    }

    // ===================================================================
    //  Download creation
    // ===================================================================

    private fun createAndStartDownload(
        url: String, fileName: String, mimeType: String?,
        userAgent: String?, referer: String?, origin: String?,
        cookies: String?, source: String, forceExtension: String?
    ) {
        val downloadId = persistence.idCounter.incrementAndGet()

        val targetDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Nexa"
        ).also { if (!it.exists()) it.mkdirs() }

        val cleaned = if (forceExtension != null)
            FileNameResolver.sanitiseWithForcedExtension(fileName, forceExtension)
        else
            FileNameResolver.sanitise(fileName, mimeType)

        val uniqueName = FileNameResolver.uniqueName(targetDir, cleaned)
        if (uniqueName != cleaned) {
            Log.d(TAG, "Unique name: $uniqueName (was $cleaned)")
        }

        val filePath = File(targetDir, uniqueName).absolutePath

        val item = DownloadItem(
            id = downloadId, url = url, fileName = uniqueName,
            filePath = filePath, status = DownloadStatus.PENDING,
            mimeType = mimeType, userAgent = userAgent, referer = referer,
            origin = origin, cookies = cookies, source = source
        )

        downloadItems[downloadId] = item
        persistence.markDirty()
        emit()

        // Enqueue in the custom download engine
        engine.enqueue(item)
    }

    // ===================================================================
    //  Status & progress updates
    // ===================================================================

    private fun updateStatus(item: DownloadItem, newStatus: DownloadStatus) {
        // NOTE: With shared DownloadItem instances, item.status is ALREADY set
        // to newStatus by DownloadTask before this method is called. We must NOT
        // use an early-return guard like `if (old == newStatus) return` — that
        // would skip the notification update and emission for every status change.
        item.status = newStatus

        if (newStatus == DownloadStatus.COMPLETED || newStatus == DownloadStatus.CANCELLED) {
            item.failureCount = 0
        }

        notifManager?.updateNotification(item, downloadItems.values)
        persistence.markDirty()
        emit()
    }

    private fun updateProgress(item: DownloadItem) {
        // Item is shared between engine and service — fields are already up-to-date
        if (!downloadItems.containsKey(item.id)) return

        // Always emit to observers (lightweight, no rate limit)
        emit()

        // Throttle notification updates to avoid Android's rate limit (5/sec).
        // Without this, 8 segments × multiple downloads can easily exceed 5/sec.
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateTime = now
            notifManager?.updateNotification(item, downloadItems.values)
        }

        persistence.markDirty()
    }

    /**
     * Pushes the current [downloadItems] map to [_downloads] as a sorted
     * snapshot. Sort order preserved from `DownloadService.getDownloadItems`.
     */
    private fun emit() {
        _downloads.value = downloadItems.values
            .map { it.copy() }
            .sortedWith(
                compareBy<DownloadItem> {
                    when (it.status) {
                        DownloadStatus.DOWNLOADING -> 0
                        DownloadStatus.PENDING -> 1
                        else -> 2
                    }
                }.thenByDescending { it.createdAt }
            )
    }

    // ===================================================================
    //  Persistence
    // ===================================================================

    private fun loadDownloadState() {
        val items = persistence.load()
        downloadItems.clear()

        if (items.isEmpty()) return

        items.forEach { item ->
            downloadItems[item.id] = item

            // Restore task in the engine so it can be resumed
            engine.restoreTask(item)
        }

        Log.d(TAG, "Loaded ${items.size} download items, restored in engine")
    }

    /** Periodically flushes dirty state to disk every 2 seconds. */
    private fun startPeriodicFlush() {
        flushJob = appScope.launch {
            while (isActive) {
                delay(2_000)
                withContext(Dispatchers.IO) {
                    // Snapshot to avoid torn reads from concurrent progress mutations
                    persistence.flushIfDirty(downloadItems.values.map { it.copy() })
                }
            }
        }
    }

    // ===================================================================
    //  Network monitoring
    // ===================================================================

    /**
     * Registers a network callback to detect connectivity changes.
     * When internet returns, auto-resumes downloads that were paused due to network loss.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "Network available — checking for paused downloads")
                appScope.launch(Dispatchers.Main.immediate) {
                    handleNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost")
                // Show network-wait notification for active downloads
                appScope.launch(Dispatchers.Main.immediate) {
                    downloadItems.values
                        .filter { it.status == DownloadStatus.DOWNLOADING }
                        .forEach { notifManager?.showNetworkWaitNotification(it) }
                }
            }
        }
        networkCallback = cb

        try {
            connectivityManager.registerNetworkCallback(request, cb)
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            connectivityManager.unregisterNetworkCallback(cb)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback", e)
        } finally {
            networkCallback = null
        }
    }

    /**
     * Resumes all downloads that were auto-paused due to network loss.
     * Called on main thread when connectivity is restored.
     */
    private fun handleNetworkAvailable() {
        if (attachedService == null) return

        val waitingDownloads = downloadItems.values.filter {
            it.status == DownloadStatus.PAUSED && it.wasWaitingForNetwork
        }

        if (waitingDownloads.isEmpty()) return

        Log.d(TAG, "Auto-resuming ${waitingDownloads.size} network-paused download(s)")

        waitingDownloads.forEach { item ->
            item.wasWaitingForNetwork = false
            notifManager?.showResumeNotification(item)

            if (engine.hasTask(item.id)) {
                engine.resume(item.id)
            } else {
                engine.restoreTask(item)
                engine.resume(item.id)
            }

            Log.d(TAG, "Auto-resumed: ${item.fileName}")
        }
    }

    // ===================================================================
    //  Helpers
    // ===================================================================

    private fun scanMediaFile(filePath: String, mimeType: String?) {
        try {
            val file = safeDownloadedFile(filePath) ?: run {
                Log.w(TAG, "Refusing to scan file outside download directory: $filePath")
                return
            }
            if (!file.exists()) {
                Log.w(TAG, "Cannot scan — file missing: $filePath")
                return
            }
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf(mimeType)
            ) { path, uri ->
                if (uri != null) Log.d(TAG, "Scanned: $path -> $uri")
                else Log.w(TAG, "Scan returned null URI: $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning file: $filePath", e)
        }
    }

    private fun deleteDownloadedFile(path: String) {
        try {
            val file = safeDownloadedFile(path) ?: run {
                Log.w(TAG, "Refusing to delete file outside download directory: $path")
                return
            }
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted: $path")
                } else {
                    // If immediate delete failed (file handle still open briefly),
                    // retry on the app scope so the work survives the service without
                    // blocking a raw thread between attempts.
                    Log.w(TAG, "Immediate delete failed, scheduling retry: $path")
                    appScope.launch(Dispatchers.IO) {
                        try {
                            for (attempt in 1..3) {
                                delay(500L * attempt)
                                val retryFile = File(path)
                                if (!retryFile.exists()) {
                                    Log.d(TAG, "File already gone on retry $attempt: $path")
                                    return@launch
                                }
                                if (retryFile.delete()) {
                                    Log.d(TAG, "Deleted on retry $attempt: $path")
                                    return@launch
                                }
                            }
                            Log.e(TAG, "Failed to delete after all retries: $path")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error on retry delete: $path", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting: $path", e)
        }
    }

    companion object {
        private const val TAG = "DownloadRepository"

        /** Minimum interval between notification updates (ms) to avoid Android rate limiting. */
        private const val NOTIFICATION_THROTTLE_MS = 500L

        /** Statuses that count as "active" for duplicate-URL detection. */
        private val ACTIVE_STATUSES = setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING)
    }

    private fun safeDownloadedFile(path: String): File? {
        return try {
            val root = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Nexa"
            ).canonicalFile
            val file = File(path).canonicalFile
            if (file.path == root.path || file.path.startsWith(root.path + File.separator)) file else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate download path: $path", e)
            null
        }
    }
}
