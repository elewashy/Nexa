package com.elewashy.nexa.feature.downloads.data

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.elewashy.nexa.core.files.DownloadDirectory
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.feature.downloads.data.engine.DownloadEngine
import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask
import com.elewashy.nexa.feature.downloads.data.engine.HttpProber
import com.elewashy.nexa.feature.downloads.data.filename.FileNameResolver
import com.elewashy.nexa.feature.downloads.data.notification.DownloadNotificationManager
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadSnapshot
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadStore
import com.elewashy.nexa.feature.downloads.data.persistence.LegacyDownloadStateReader
import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.data.persistence.toEntity
import com.elewashy.nexa.feature.downloads.data.persistence.toItem
import com.elewashy.nexa.feature.downloads.data.persistence.toSegmentEntityOrNull
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [DownloadRepository].
 *
 * Owns the full download stack:
 *  - [DownloadEngine]  (segmented parallel downloads)
 *  - [DownloadStore]  (Room-backed structural state + coalesced progress)
 *  - [DownloadNotificationManager]  (all user-facing notifications)
 *  - [ConcurrentHashMap] of live [DownloadItem]s (shared with the engine)
 *  - [ConnectivityManager.NetworkCallback]  (auto-resume on network return)
 *
 * Persistence model: high-frequency progress only marks ids dirty; the store's
 * single-writer batch loop persists coalesced snapshots, while structural
 * events (create/status/cancel) write immediately. Runtime-only values
 * (speed/ETA/failure tally) never reach the database.
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
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val store: DownloadStore,
    private val runtimeSettings: DownloadRuntimeSettings = DownloadRuntimeSettings(),
) : DownloadRepository {

    // ── State ──────────────────────────────────────────────────────────

    private val downloadItems = ConcurrentHashMap<Long, DownloadItem>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val downloadsState = _downloads.asStateFlow()
    override val downloads: StateFlow<List<DownloadItem>>
        get() {
            // Kick off async init without blocking the caller — collectors simply
            // observe the empty list until persisted state is loaded and emitted.
            ensureInitialised()
            return downloadsState
        }

    private val _notificationsWarning = MutableStateFlow<String?>(null)
    override val notificationsWarning: StateFlow<String?> = _notificationsWarning.asStateFlow()

    /** One-shot latch so the disabled-notifications warning fires once per process. */
    private var notificationsWarningEmitted = false

    /**
     * URLs reserved by in-flight `start()` calls. Dedup must happen synchronously
     * BEFORE any dispatcher hop — otherwise two concurrent starts for the same
     * URL both pass the item-list check while neither item exists yet, and
     * stopServiceIfIdle could race the reservation window.
     * Main thread only (all mutations happen inside `Dispatchers.Main.immediate`).
     */
    private val reservedUrls = mutableSetOf<String>()

    /**
     * Filenames reserved by downloads that can still claim that name.
     * `uniqueName()` only sees the filesystem: two concurrent creations can
     * resolve the same name before either `.part` file exists, ending up
     * sharing one `.part` path (corruption). Reservations close that window.
     * Released when the name can no longer be claimed: COMPLETED (the final
     * file on disk now guards it) or removal/cancellation. Kept while FAILED —
     * a retry reuses [DownloadItem.filePath] without re-resolving the name.
     * Re-populated from persisted state at init. Main thread only.
     */
    private val reservedFileNames = mutableSetOf<String>()

    /**
     * Ids whose resume was routed through `startForegroundService` but whose
     * intent has not been handled yet. [stopServiceIfIdle] must not stop the
     * service inside that window — the queued RESUME intent needs it attached.
     * Main thread only.
     */
    private val routedResumes = mutableSetOf<Long>()

    // ── Delegates ──────────────────────────────────────────────────────

    /**
     * Notification manager — instantiated on first [attachService] and reused
     * thereafter. Holds a `Service` reference internally, so we rebuild it when
     * the hosting service changes (e.g. process restart).
     */
    private var notifManager: DownloadNotificationManager? = null

    /** Download id allocator; seeded from Room metadata at init. */
    private val idCounter = AtomicLong(0)

    /**
     * Last persisted segment rows per download, mirroring what Room holds.
     * Seeds [DownloadTask] restores and serves the empty-snapshot guard (a
     * restored-but-unresumed task must not wipe its own resume state).
     */
    private val restoredSegmentCache = ConcurrentHashMap<Long, List<PersistedSegment>>()

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

    /**
     * Automatic retry for FAILED downloads. Transient failures (server
     * hiccups, unreachable resume probes, mid-stream resets) are retried on a
     * backoff schedule before the download is left failed for manual action.
     * Both maps are main-thread only (all mutations happen on Main.immediate).
     */
    @Volatile private var autoRetryEnabled = true
    private val autoRetryCounts = mutableMapOf<Long, Int>()
    private val autoRetryJobs = mutableMapOf<Long, Job>()

    /**
     * Last progress-notification update per download. Android may drop overly frequent
     * notification updates, so progress is throttled per item while status transitions always render.
     */
    private val lastNotificationUpdateById = mutableMapOf<Long, Long>()

    /** UI emission throttle state — see [emit]. Main thread only. */
    private var lastUiEmitTime = 0L
    private var uiEmitPending = false

    // ── Network monitoring ────────────────────────────────────────────

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── Initialisation ────────────────────────────────────────────────

    /**
     * Initialisation is idempotent and runs exactly once per process. Loads the
     * persisted state, primes the engine with restored tasks, and kicks off the
     * periodic flush. The network callback is service-scoped because auto-resume
     * must have a foreground service attached.
     *
     * Heavy work (JSON read + Gson parse + orphan-.part sweep) runs on
     * appScope+IO — never on the caller thread, which is main when the
     * Downloads screen opens without the service running. Callers that need
     * the loaded state suspend on [awaitInitialised].
     */
    @Volatile
    private var initGate = CompletableDeferred<Unit>()

    /** Set as soon as the init coroutine is launched — the gate completes later. */
    private var initStarted = false

    /**
     * True once persisted state has been loaded into [downloadItems]. Flushes
     * are gated on this: a detach/periodic flush that runs before the async
     * load finishes would otherwise write an EMPTY snapshot over the state
     * file (sticky service restart + stopServiceIfIdle → detach) and wipe
     * the entire download history.
     */
    @Volatile
    private var stateLoaded = false

    init {
        // DownloadTask supplies a locked aggregate snapshot; map/cache access
        // here is concurrent-safe, so Room's writer thread never blocks main.
        store.snapshotProvider = { ids -> captureSnapshots(ids) }
        appScope.launch {
            runtimeSettings.maxConcurrentDownloads.collect(engine::updateMaxConcurrentDownloads)
        }
        appScope.launch {
            runtimeSettings.speedLimitBytesPerSecond.collect(engine::updateSpeedLimit)
        }
        appScope.launch(Dispatchers.Main.immediate) {
            runtimeSettings.autoRetry.collect { enabled ->
                autoRetryEnabled = enabled
                if (!enabled) {
                    autoRetryJobs.values.forEach(Job::cancel)
                    autoRetryJobs.clear()
                    autoRetryCounts.clear()
                } else if (stateLoaded) {
                    downloadItems.values
                        .filter { it.status == DownloadStatus.FAILED }
                        .forEach(::scheduleAutoRetry)
                }
            }
        }
    }

    @Synchronized
    private fun ensureInitialised() {
        if (initStarted) return
        initStarted = true
        val gate = initGate
        appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loadDownloadState()
                }
                stateLoaded = true
                withContext(Dispatchers.Main.immediate) {
                    // Promote to foreground only when restored work is genuinely
                    // ACTIVE — a sticky restart with only paused work must not
                    // hold a dataSync FGS or show a phantom Preparing notification.
                    attachedService?.takeIf { hasActiveWork() }?.let { service ->
                        notifManager?.startForegroundImmediately(service)
                    }
                    // Restored records re-reserve their filenames: a restored
                    // item whose .part is gone must not lose its name to a new
                    // download — resuming it later would collide on the .part.
                    downloadItems.values.forEach { item ->
                        if (item.status != DownloadStatus.COMPLETED) {
                            reservedFileNames.add(item.fileName)
                        }
                    }
                    notifManager?.syncNotifications(downloadItems.values)
                    store.startBatchLoop()
                    // Recover retryable failures from the previous session.
                    // Retries that could not start the foreground service from
                    // the background are left failed (no re-arm loop); a new
                    // session is their wake point.
                    downloadItems.values
                        .filter { it.status == DownloadStatus.FAILED }
                        .forEach { scheduleAutoRetry(it) }
                    emit(force = true)
                }
                Log.d(TAG, "DownloadRepository initialised")
            } catch (e: CancellationException) {
                stateLoaded = false
                downloadItems.clear()
                restoredSegmentCache.clear()
                engine.dropRestoredTasks()
                synchronized(this@DownloadRepositoryImpl) {
                    initStarted = false
                    initGate = CompletableDeferred()
                }
                throw e
            } catch (e: Exception) {
                stateLoaded = false
                downloadItems.clear()
                restoredSegmentCache.clear()
                engine.dropRestoredTasks()
                // Never fatal for the current caller, but must not latch the
                // repository into permanently unpersisted operation: reset the
                // latch and install a fresh gate so the next access retries.
                Log.e(TAG, "Initialisation failed — will retry on next access", e)
                synchronized(this@DownloadRepositoryImpl) {
                    initStarted = false
                    initGate = CompletableDeferred()
                }
            } finally {
                gate.complete(Unit)
            }
        }
    }

    /** Suspends until persisted state is loaded (see [ensureInitialised]). */
    private suspend fun awaitInitialised() {
        ensureInitialised()
        initGate.await()
        check(stateLoaded) { "Download persistence initialization failed" }
    }

    /** Genuinely active work — paused items must not hold a dataSync FGS. */
    private fun hasActiveWork(): Boolean =
        downloadItems.values.any { it.status in ACTIVE_STATUSES }

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
        // Satisfy it immediately with a DEFERRED notification; it only becomes
        // visible if the FGS is held long enough, and paused-only services are
        // stopped via stopServiceIfIdle before that happens.
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

        // The service going away pauses active work. Render that state into the same
        // per-download notification id instead of cancelling it; otherwise users see
        // disappearing/stuck progress notifications and lose the Resume action.
        active.forEach {
            it.status = DownloadStatus.PAUSED
            it.downloadSpeedBytesPerSecond = 0
            notifManager?.updateNotification(it, downloadItems.values)
        }
        notifManager?.updateSummary(downloadItems.values)

        // Final persistence: capture the consistent snapshot here on main
        // (cheap, bounded, no I/O) and hand the write to the serialized
        // writer without blocking service destruction. Structural statuses
        // were already persisted immediately at their transitions; this lands
        // the latest batched progress. If the process dies before the async
        // write lands, resume re-verifies against the .part file, so state
        // stays recoverable.
        if (stateLoaded) {
            store.postSnapshots(captureSnapshots(downloadItems.keys.toList()))
        }

        unregisterNetworkCallback()
        notifManager = null

        attachedService = null
        emit(force = true) // Items flipped to PAUSED outside updateStatus — show it now
        Log.d(TAG, "DownloadRepository detached from service")
    }

    override fun dismissNotificationsWarning() {
        _notificationsWarning.value = null
    }

    /**
     * dataSync FGS quota exhausted. Pause everything, tell the user why, and let
     * [DownloadNotificationManager.updateSummary] demote the service out of the
     * foreground (paused-only work must not hold a dataSync FGS). The service
     * then stops itself — Android kills the process if `onTimeout` doesn't.
     */
    override fun handleForegroundTimeout() {
        val active = downloadItems.values.filter { it.status in ACTIVE_STATUSES }
        Log.w(TAG, "Foreground-service timeout — pausing ${active.size} active download(s)")

        active.forEach { item -> engine.pause(item.id) }

        // Demote FIRST: STOP_FOREGROUND_REMOVE cancels all grouped notifications,
        // so the explanatory per-item notifications must be posted AFTER it or
        // they vanish. (Engine PAUSED callbacks arrive asynchronously — the app
        // scope runs on Dispatchers.Default — so nothing else reposts them.)
        notifManager?.demoteFromForeground()
        active.forEach { item ->
            item.status = DownloadStatus.PAUSED
            item.downloadSpeedBytesPerSecond = 0
            notifManager?.showSystemTimeoutPausedNotification(item)
        }
        notifManager?.updateSummary(downloadItems.values)
        // task.pause() set PAUSED synchronously; persist the transition now so
        // a kill after the quota timeout can't lose it.
        store.postUpsert(active.map { it.id })
    }

    override fun stopServiceIfIdle() {
        val svc = attachedService ?: return
        // A start() may still be between reservation and item creation —
        // reservations land synchronously in start()'s entry section, so this
        // check is race-free with new starts.
        if (reservedUrls.isNotEmpty()) return
        // A routed resume intent is queued but not handled yet — the service
        // it targets must survive until then.
        if (routedResumes.isNotEmpty()) return
        // Only genuinely ACTIVE work keeps the service: paused items must not
        // hold a dataSync FGS (6h quota), and resuming them restarts the
        // service on demand via the notification/UI intents.
        if (hasActiveWork()) return
        // Network-waiting items need the registered connectivity callback to
        // auto-resume — stopping the service would unregister it and strand
        // them until a manual resume.
        if (downloadItems.values.any { it.wasWaitingForNetwork }) return
        Log.d(TAG, "No active work — stopping download service")
        svc.stopSelf()
    }

    // ===================================================================
    //  Public commands
    // ===================================================================

    override suspend fun start(request: DownloadRequest) = withContext(Dispatchers.Main.immediate) {
        // Reservation lands synchronously in the entry section, BEFORE any
        // dispatcher hop — stopServiceIfIdle checks it, so a service shutdown
        // can never race the window between this call and item creation.
        if (!reservedUrls.add(request.url)) {
            Log.d(TAG, "Download already active for URL: ${request.url}")
            return@withContext
        }

        awaitInitialised()

        // Dedup against items that now exist (persisted state is loaded).
        val alreadyActive = downloadItems.values.any {
            it.url == request.url && it.status in ACTIVE_STATUSES
        }
        if (alreadyActive) {
            reservedUrls.remove(request.url)
            Log.d(TAG, "Download already active for URL: ${request.url}")
            return@withContext
        }

        // Resolve filename + size + range support in ONE probe pass (IO-bound, on
        // the application scope so it survives the caller's lifecycle). The probe
        // result is handed to the engine so the URL is never hit twice.
        try {
            appScope.launch {
                val probe = FileNameResolver.probeOnce(
                    request.url, request.userAgent, request.referer, request.cookies
                )

                val finalName = probe.fileName ?: request.fileName
                val effectiveMime = if (probe.contentType != null && request.forceExtension == null) {
                    probe.contentType
                } else {
                    request.mimeType
                }

                Log.d(TAG, "Starting download: $finalName (MIME=$effectiveMime, forceExt=${request.forceExtension})")
                withContext(Dispatchers.Main.immediate) {
                    try {
                        createAndStartDownload(
                            url = request.url,
                            fileName = finalName,
                            mimeType = effectiveMime,
                            userAgent = request.userAgent,
                            referer = request.referer,
                            origin = request.origin,
                            cookies = request.cookies,
                            source = request.source,
                            forceExtension = request.forceExtension,
                            initialProbe = probe
                        )
                    } catch (cancellation: CancellationException) {
                        reservedUrls.remove(request.url)
                        throw cancellation
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create download: ${request.url}", e)
                        reservedUrls.remove(request.url)
                    }
                }
            }
        } catch (e: Exception) {
            // Scope unusable — the reservation must not leak.
            Log.e(TAG, "Failed to launch probe for: ${request.url}", e)
            reservedUrls.remove(request.url)
        }
        Unit
    }

    override suspend fun pause(id: Long) = withContext(Dispatchers.Main.immediate) {
        awaitInitialised()
        downloadItems[id]?.let { item ->
            // Manual pause supersedes any scheduled automatic retry.
            cancelAutoRetry(item.id)
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PENDING) {
                engine.pause(item.id)
                Log.d(TAG, "Pausing: ${item.fileName}")
            }
        }
        // The command may have been a no-op (already paused/completed item,
        // stale notification action) — don't leave a deferred FGS lingering.
        stopServiceIfIdle()
        Unit
    }

    override suspend fun resume(id: Long) = withContext(Dispatchers.Main.immediate) {
        awaitInitialised()
        routedResumes.remove(id)
        downloadItems[id]?.let { item ->
            // A manual resume resets the automatic-retry budget.
            cancelAutoRetry(item.id)
            if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
                if (!resumeInternal(item)) {
                    // Blocked (missing permission) or nothing resumable — the
                    // service this command started must not be left idle.
                    stopServiceIfIdle()
                }
            } else {
                stopServiceIfIdle()
            }
        } ?: stopServiceIfIdle()
        Unit
    }

    /**
     * Starts/resumes the given item. Caller must be on the main thread and
     * have checked the item is PAUSED/FAILED. Shared by manual resume and
     * the automatic-retry scheduler.
     *
     * @return `true` when the item is now running or queued to run (possibly
     *         via a service start that completes asynchronously); `false` when
     *         nothing was started (missing permission, service not startable
     *         from the current context) — callers use that to stop an idle
     *         service or defer a retry instead of stranding the item.
     */
    private fun resumeInternal(item: DownloadItem): Boolean {
        if (!hasStorageWritePermission()) {
            // Keep the actionable error instead of failing mid-write in the engine
            item.errorMessage = DownloadTask.ERROR_STORAGE_PERMISSION
            updateStatus(item, DownloadStatus.FAILED)
            Log.w(TAG, "Resume blocked — storage permission missing: ${item.fileName}")
            return false
        }

        if (attachedService == null) {
            // A download must run under the foreground service or the system
            // kills it as soon as the app is backgrounded. This happens when
            // the user resumes from the Downloads screen after the idle
            // service stopped itself — route through the service, which calls
            // back into resume() once attached.
            Log.d(TAG, "Resume routed through service (not attached): ${item.fileName}")
            return routeResumeThroughService(item)
        }

        item.errorMessage = null
        resumeInEngine(item)
        return true
    }

    /**
     * Asks the foreground service to resume [item] once it is attached. The id
     * is tracked in [routedResumes] so [stopServiceIfIdle] cannot stop the
     * service before the queued intent is handled.
     *
     * @return `false` when the platform denied the start (Android 12+
     *         background-start restrictions) — the item keeps its current
     *         status for a later retry or manual action.
     */
    private fun routeResumeThroughService(item: DownloadItem): Boolean {
        routedResumes.add(item.id)
        return try {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_RESUME_DOWNLOAD)
                    .putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
            )
            true
        } catch (e: Exception) {
            routedResumes.remove(item.id)
            Log.e(TAG, "Failed to start service for: ${item.fileName}", e)
            false
        }
    }

    /**
     * Resumes [item] in the engine, re-enqueueing it with its persisted
     * segment state when the engine no longer holds the task (restored from
     * Room) so resume is verified against the `.part` file on disk.
     */
    private fun resumeInEngine(item: DownloadItem) {
        if (engine.hasTask(item.id)) {
            Log.d(TAG, "Resuming: ${item.fileName}")
        } else {
            Log.d(TAG, "Re-enqueueing: ${item.fileName}")
            engine.restoreTask(item, restoredSegments(item.id))
        }
        engine.resume(item.id)
    }

    override suspend fun retry(id: Long) = resume(id)

    override suspend fun renameCompleted(
        id: Long,
        requestedName: String,
    ): RenameDownloadResult = withContext(Dispatchers.Main.immediate) {
        awaitInitialised()
        val item = downloadItems[id] ?: return@withContext RenameDownloadResult.NotFound
        if (item.status != DownloadStatus.COMPLETED) {
            return@withContext RenameDownloadResult.NotCompleted
        }
        val trimmedName = requestedName.trim()
        val existingExtension = item.fileName.substringAfterLast('.', "")
        val requestedWithExtension = if (
            trimmedName.substringAfterLast('.', "").isBlank() && existingExtension.isNotBlank()
        ) {
            "$trimmedName.$existingExtension"
        } else {
            trimmedName
        }
        val cleanName = FileNameResolver.sanitise(requestedWithExtension, item.mimeType)
        if (trimmedName.isBlank() || cleanName.isBlank()) {
            return@withContext RenameDownloadResult.InvalidName
        }

        val source = File(item.filePath)
        if (!source.exists()) return@withContext RenameDownloadResult.NotFound
        if (cleanName == item.fileName) return@withContext RenameDownloadResult.Success
        val target = File(source.parentFile, cleanName)
        if (target.exists() || File(target.path + DownloadTask.PART_SUFFIX).exists()) {
            return@withContext RenameDownloadResult.NameAlreadyExists
        }

        val moved = withContext(Dispatchers.IO) { moveFile(source, target) }
        if (!moved) return@withContext RenameDownloadResult.FileOperationFailed

        val previousName = item.fileName
        val previousPath = item.filePath
        item.fileName = cleanName
        item.filePath = target.absolutePath
        try {
            store.upsertNow(listOf(id))
            emit(force = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(previousPath, target.absolutePath),
                arrayOf(item.mimeType, item.mimeType),
                null,
            )
            RenameDownloadResult.Success
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist rename for $id", error)
            val rolledBack = withContext(NonCancellable + Dispatchers.IO) { moveFile(target, source) }
            if (rolledBack) {
                item.fileName = previousName
                item.filePath = previousPath
            } else {
                // Keep in-memory state aligned with the filesystem and retain a durable retry.
                store.postUpsert(listOf(id))
                emit(force = true)
            }
            if (error is CancellationException) throw error
            RenameDownloadResult.PersistenceFailed
        }
    }

    private fun moveFile(source: File, target: File): Boolean = try {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath())
        }
        true
    } catch (error: Exception) {
        Log.w(TAG, "Unable to rename ${source.name} to ${target.name}: ${error.message}")
        false
    }

    override suspend fun cancel(id: Long) = cancelInternal(id, deleteFile = true)

    override suspend fun remove(id: Long) = cancelInternal(id, deleteFile = false)

    private suspend fun cancelInternal(id: Long, deleteFile: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            awaitInitialised()
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
                reservedFileNames.remove(item.fileName)

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
                    // Restored-but-never-started tasks have no writer, so the engine
                    // can't delete their .part file — remove it explicitly.
                    deleteDownloadedFile(filePath + DownloadTask.PART_SUFFIX)
                } else {
                    engine.remove(item.id)
                    Log.d(TAG, "Removing from list: ${item.fileName}")
                    // No final file exists for unfinished downloads — drop the .part
                    // so removing an unfinished item doesn't orphan partial data.
                    deleteDownloadedFile(filePath + DownloadTask.PART_SUFFIX)
                }

                cancelAutoRetry(item.id)
                restoredSegmentCache.remove(item.id)
                lastNotificationUpdateById.remove(item.id)
                store.postDelete(item.id)
                emit(force = true) // Item removed — structural change, show it now
                stopServiceIfIdle()
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
                cancelAutoRetry(item.id)
                updateStatus(item, DownloadStatus.DOWNLOADING)
            }
            DownloadStatus.PAUSED -> {
                cancelAutoRetry(item.id)
                item.downloadSpeedBytesPerSecond = 0
                updateStatus(item, DownloadStatus.PAUSED)

                if (item.wasWaitingForNetwork) {
                    notifManager?.showNetworkWaitNotification(item)
                }
            }
            DownloadStatus.COMPLETED -> {
                cancelAutoRetry(item.id)
                item.downloadSpeedBytesPerSecond = 0
                // The final file now guards the name on disk — release the
                // reservation or the set grows with every completed download.
                reservedFileNames.remove(item.fileName)
                // Completion is terminal — the resume cache is only consumed
                // when restoring resumable tasks; keep it from accumulating.
                restoredSegmentCache.remove(item.id)
                lastNotificationUpdateById.remove(item.id)
                updateStatus(item, DownloadStatus.COMPLETED)
                scanMediaFile(item.filePath, item.mimeType)
            }
            DownloadStatus.FAILED -> {
                updateStatus(item, DownloadStatus.FAILED)
                scheduleAutoRetry(item)
            }
            DownloadStatus.CANCELLED -> {
                // CANCELLED is normally handled by cancelInternal().
                // This path only fires if the engine cancels internally
                // (e.g., a cancel was requested while still in the queue).
                cancelAutoRetry(item.id)
                if (downloadItems.containsKey(item.id)) {
                    item.status = DownloadStatus.CANCELLED
                    downloadItems.remove(item.id)
                    reservedFileNames.remove(item.fileName)
                    restoredSegmentCache.remove(item.id)
                    lastNotificationUpdateById.remove(item.id)
                    notifManager?.cancelNotification(item.id)
                    notifManager?.updateSummary(downloadItems.values)
                    store.postDelete(item.id)
                    emit(force = true) // Item removed — structural change, show it now
                }
            }
        }

        // Terminal states may have emptied the work queue — stop the service
        // instead of letting a started service linger until process death.
        stopServiceIfIdle()
    }

    // ===================================================================
    //  Automatic retry
    // ===================================================================

    /**
     * Schedules a retry for a FAILED download on the backoff schedule.
     * Permanent failures (dead URL, missing file, missing permission) are
     * left failed immediately. Must run on the main thread.
     */
    private fun scheduleAutoRetry(item: DownloadItem) {
        if (!autoRetryEnabled) return
        if (item.errorMessage in NON_RETRYABLE_ERRORS) return

        val attempt = autoRetryCounts.getOrDefault(item.id, 0)
        if (attempt >= AUTO_RETRY_DELAYS_MS.size) {
            Log.w(TAG, "Auto-retry budget exhausted for: ${item.fileName}")
            return
        }

        autoRetryJobs[item.id]?.cancel()
        val delayMs = AUTO_RETRY_DELAYS_MS[attempt]
        autoRetryCounts[item.id] = attempt + 1
        Log.i(TAG, "Auto-retry ${attempt + 1}/${AUTO_RETRY_DELAYS_MS.size} " +
                "for ${item.fileName} in ${delayMs / 1000}s")

        autoRetryJobs[item.id] = appScope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            autoRetryJobs.remove(item.id)

            val current = downloadItems[item.id] ?: return@launch
            if (current.status != DownloadStatus.FAILED) return@launch

            // resumeInternal routes through the foreground service when it is
            // not attached, so the retry never runs outside an FGS.
            Log.i(TAG, "Auto-retrying: ${current.fileName}")
            if (!resumeInternal(current)) {
                // Service not startable from the background (Android 12+).
                // Leave the item failed — the next session's init sweep or a
                // manual resume retries it. Re-arming here would loop forever
                // against the background-start restriction.
                Log.w(TAG, "Auto-retry deferred to next session (background): ${current.fileName}")
            }
        }
    }

    /** Cancels a pending auto-retry and resets its budget. Main thread only. */
    private fun cancelAutoRetry(id: Long) {
        autoRetryJobs.remove(id)?.cancel()
        autoRetryCounts.remove(id)
    }

    // ===================================================================
    //  Download creation
    // ===================================================================

    private fun createAndStartDownload(
        url: String, fileName: String, mimeType: String?,
        userAgent: String?, referer: String?, origin: String?,
        cookies: String?, source: String, forceExtension: String?,
        initialProbe: HttpProber.ProbeResult? = null
    ) {
        try {
            val downloadId = idCounter.incrementAndGet()
            appScope.launch {
                try {
                    store.setLastId(downloadId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Sequence persistence is best-effort; the id clamp at
                    // load time (max of stored and row ids) heals gaps.
                    Log.w(TAG, "setLastId failed: ${e.message}")
                }
            }

            val targetDir = DownloadDirectory.ensureRoot()

            val cleaned = if (forceExtension != null)
                FileNameResolver.sanitiseWithForcedExtension(fileName, forceExtension)
            else
                FileNameResolver.sanitise(fileName, mimeType)

            val uniqueName = FileNameResolver.uniqueName(targetDir, cleaned, reservedFileNames)
            if (uniqueName != cleaned) {
                Log.d(TAG, "Unique name: $uniqueName (was $cleaned)")
            }
            // Reserve before anything else can resolve the same name — released
            // only when the item reaches a terminal state.
            reservedFileNames.add(uniqueName)

            val filePath = File(targetDir, uniqueName).absolutePath

            val item = DownloadItem(
                id = downloadId, url = url, fileName = uniqueName,
                filePath = filePath, status = DownloadStatus.PENDING,
                mimeType = mimeType, userAgent = userAgent, referer = referer,
                origin = origin, cookies = cookies, source = source
            )

            // Storage gate — the engine writes the public Downloads/Nexa directory
            // via direct file IO. Fail fast with an actionable error through the
            // regular FAILED path instead of failing mid-write deep in the engine.
            if (!hasStorageWritePermission()) {
                Log.w(TAG, "Storage permission missing — download not started: $url")
                item.status = DownloadStatus.FAILED
                item.errorMessage = DownloadTask.ERROR_STORAGE_PERMISSION
                downloadItems[downloadId] = item
                // No engine.enqueue here — a queued task would sit on an
                // uninitialised writer until the user resumes.
                persistNow(downloadId)
                updateStatus(item, DownloadStatus.FAILED)
                // Release the URL reservation first (the finally would only run
                // afterwards) so stopServiceIfIdle can actually stop the service.
                reservedUrls.remove(url)
                stopServiceIfIdle()
                return
            }

            downloadItems[downloadId] = item

            // A download is starting while notifications are disabled — without a
            // warning the user would get zero feedback about it. One shot per process.
            maybeEmitNotificationsWarning()

            emit(force = true) // New item — structural change, show it now

            // Persist the record before the engine creates the .part file: a
            // kill between enqueue and a fire-and-forget persist would leave
            // an orphan .part that the next start's sweep deletes.
            appScope.launch(Dispatchers.Main.immediate) {
                try {
                    store.upsertNow(listOf(downloadId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The item stays in memory and re-persists at the next
                    // structural transition; the download itself proceeds.
                    Log.e(TAG, "Initial record persist failed for $downloadId", e)
                }
                if (attachedService == null) {
                    // The probe window can outlive the foreground service (system
                    // pressure, Android 15 dataSync timeout). A download running
                    // outside the FGS is killed as soon as the app is backgrounded,
                    // so persist it paused and route through the service exactly
                    // like a resume — it starts once the service attaches.
                    Log.d(TAG, "Start routed through service (not attached): ${item.fileName}")
                    item.status = DownloadStatus.PAUSED
                    persistNow(downloadId)
                    emit(force = true)
                    // Background-start restrictions leave the item paused for a
                    // manual resume instead of running unprotected.
                    routeResumeThroughService(item)
                } else {
                    // Enqueue in the custom download engine, reusing the upstream
                    // probe so the URL is only hit once.
                    engine.enqueue(item, initialProbe = initialProbe)
                }
            }
        } finally {
            // Reservation is handed over to the downloadItems dedup (or dropped on
            // failure) — either way it must not leak.
            reservedUrls.remove(url)
        }
    }

    /**
     * Storage write access for the public Downloads/Nexa directory:
     *  - API 30+: MANAGE_EXTERNAL_STORAGE (Environment.isExternalStorageManager).
     *  - API 29: WRITE_EXTERNAL_STORAGE runtime permission. Scoped storage is
     *    enforced for apps targeting R+, and the manifest's WRITE_EXTERNAL_STORAGE
     *    declaration is capped at maxSdkVersion=28 — returning true here used to
     *    make writes fail opaquely mid-download. Gate on the actual grant instead
     *    (an all-files-access grant also satisfies it), so the failure surfaces
     *    as the actionable FAILED error.
     *  - API 26–28: legacy WRITE_EXTERNAL_STORAGE runtime permission.
     */
    private fun hasStorageWritePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED || hasAllFilesAccessOnQ()
        else ->
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * All-files-access (MANAGE_EXTERNAL_STORAGE) grant check for API 29, where
     * [Environment.isExternalStorageManager] does not exist yet — query the
     * underlying app-op directly. Unknown/ungranted ops return false.
     */
    // unsafeCheckOpNoThrow is deprecated but is the only API that can query
    // the all-files-access app-op on API 29 (isExternalStorageManager is 30+).
    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasAllFilesAccessOnQ(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                "android:manage_external_storage", Process.myUid(), context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "All-files-access app-op check failed: ${e.message}")
            false
        }
    }

    private fun maybeEmitNotificationsWarning() {
        if (notificationsWarningEmitted) return
        if (notifManager?.areNotificationsEnabled() != false) return
        notificationsWarningEmitted = true
        _notificationsWarning.value = context.getString(R.string.notifications_disabled_warning)
        Log.w(TAG, "Download started while notifications are disabled")
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

        notifManager?.updateNotification(item, downloadItems.values)
        when (newStatus) {
            // Status transitions are structural: persist immediately so a
            // process kill can't regress them; the latest progress and segment
            // rows ride along in the same snapshot.
            DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED ->
                persistNow(item.id)
            // PENDING/DOWNLOADING coalesce with the batched progress writes.
            else -> store.markProgress(item.id)
        }
        if (newStatus == DownloadStatus.COMPLETED) {
            appScope.launch {
                try {
                    val prunedIds = store.pruneCompleted()
                    if (prunedIds.isNotEmpty()) {
                        withContext(Dispatchers.Main.immediate) {
                            prunedIds.forEach { id ->
                                downloadItems.remove(id)
                                engine.remove(id)
                                lastNotificationUpdateById.remove(id)
                            }
                            emit(force = true)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Prune is best-effort; the next completion retries it.
                    Log.w(TAG, "Completed prune failed: ${e.message}")
                }
            }
        }
        emit(force = true)
    }

    private fun updateProgress(item: DownloadItem) {
        // Item is shared between engine and service — fields are already up-to-date
        if (!downloadItems.containsKey(item.id)) return

        // Emit to observers, throttled (see emit) — notifications throttle separately
        emit()

        // Throttle notification updates to avoid Android's rate limit (5/sec).
        // Without this, 8 segments × multiple downloads can easily exceed 5/sec.
        val now = System.currentTimeMillis()
        val lastForItem = lastNotificationUpdateById[item.id] ?: 0L
        if (now - lastForItem >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateById[item.id] = now
            notifManager?.updateNotification(item, downloadItems.values)
        }

        // Hot path: only mark the id dirty — the store's batch loop persists
        // coalesced progress, never one write per tick.
        store.markProgress(item.id)
    }

    /**
     * Pushes the current [downloadItems] map to [_downloads] as a sorted
     * snapshot. Sort order preserved from `DownloadService.getDownloadItems`.
     *
     * Progress ticks are throttled to [UI_EMIT_THROTTLE_MS] — the deep-copy +
     * sort of the whole list on every tick is pure overhead for the UI. Status
     * changes pass `force = true` so terminal events are never delayed; a
     * trailing emission makes sure the list settles after the last tick.
     */
    private fun emit(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force) {
            uiEmitPending = false
            lastUiEmitTime = now
            pushSnapshot()
            return
        }
        if (now - lastUiEmitTime >= UI_EMIT_THROTTLE_MS) {
            uiEmitPending = false
            lastUiEmitTime = now
            pushSnapshot()
        } else if (!uiEmitPending) {
            uiEmitPending = true
            appScope.launch(Dispatchers.Main.immediate) {
                delay(UI_EMIT_THROTTLE_MS)
                if (uiEmitPending) {
                    uiEmitPending = false
                    lastUiEmitTime = System.currentTimeMillis()
                    pushSnapshot()
                }
            }
        }
    }

    private fun pushSnapshot() {
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

    private suspend fun loadDownloadState() {
        importLegacyStateIfNeeded()
        downloadItems.clear()
        restoredSegmentCache.clear()
        engine.dropRestoredTasks()

        var maxId = 0L
        store.pruneCompleted()
        store.loadAll().forEach { entity ->
            val raw = entity.toItem()
            val reconciled = reconcileWithFilesystem(raw)
            if (reconciled.status != raw.status ||
                reconciled.errorMessage != raw.errorMessage ||
                reconciled.downloadedBytes != raw.downloadedBytes
            ) {
                store.upsertEntity(reconciled.toEntity())
            }
            val item = reconciled.apply {
                // Active statuses at crash time must not auto-start on restore.
                if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PENDING) {
                    status = DownloadStatus.PAUSED
                }
            }
            downloadItems[item.id] = item
            val segments = store.segmentsFor(item.id)
            if (segments.isNotEmpty()) restoredSegmentCache[item.id] = segments
            engine.restoreTask(item, segments)
            if (item.id > maxId) maxId = item.id
        }
        val stored = store.lastId() ?: 0L
        idCounter.set(maxOf(stored, maxId))

        // A still-present legacy artifact means the import did not commit
        // (rolled back) — its .part files belong to records the next attempt
        // will restore, so sweeping now would destroy resume data.
        if (!legacyArtifactPresent()) {
            sweepOrphanPartFiles(
                downloadItems.values.mapTo(HashSet()) { it.filePath + DownloadTask.PART_SUFFIX }
            )
        }
        Log.d(TAG, "Loaded ${downloadItems.size} download items from Room")
    }

    /** True while a legacy JSON file or prefs document still awaits import. */
    private fun legacyArtifactPresent(): Boolean =
        legacyStateFile.exists() ||
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .contains(LEGACY_KEY_ITEMS)

    /**
     * Deterministic DB-vs-filesystem reconciliation at startup:
     *  - COMPLETED but the final file is gone → FAILED/ERROR_FILE_MISSING
     *    (never surface a completed download whose file is missing).
     *  - Not completed, final file present at expected size and no `.part`
     *    → the rename landed but the completion write was interrupted by the
     *    kill → promote to COMPLETED.
     *  - Everything else stays; resume verification against the `.part`
     *    remains the engine's job.
     */
    private fun reconcileWithFilesystem(item: DownloadItem): DownloadItem {
        val finalFile = File(item.filePath)
        val partFile = File(item.filePath + DownloadTask.PART_SUFFIX)
        return when {
            item.status == DownloadStatus.COMPLETED && !finalFile.exists() -> item.apply {
                status = DownloadStatus.FAILED
                errorMessage = DownloadTask.ERROR_FILE_MISSING
            }
            item.status != DownloadStatus.COMPLETED &&
                item.status != DownloadStatus.CANCELLED &&
                item.totalBytes > 0 && finalFile.exists() &&
                finalFile.length() == item.totalBytes && !partFile.exists() -> item.apply {
                status = DownloadStatus.COMPLETED
                downloadedBytes = item.totalBytes
                errorMessage = null
            }
            else -> item
        }
    }

    /**
     * Deletes `.part` files that no restored download can resume — leftovers from
     * a process death before the first persistence flush. Without this they would
     * sit in Downloads/Nexa forever.
     */
    private fun sweepOrphanPartFiles(knownPartPaths: Set<String>) {
        try {
            DownloadDirectory.root().listFiles { f -> f.isFile && f.name.endsWith(DownloadTask.PART_SUFFIX) }
                ?.filter { it.absolutePath !in knownPartPaths }
                ?.forEach { orphan ->
                    if (orphan.delete()) {
                        Log.d(TAG, "Deleted orphan part file: ${orphan.name}")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Orphan .part sweep failed: ${e.message}")
        }
    }

    /**
     * Thread-safe snapshot capture for the [DownloadStore] writer: the engine
     * supplies bytes + total + segment rows as ONE atomic snapshot, so a flush
     * can never persist an entity that disagrees with its segment rows.
     *
     * FAILED is captured like PAUSED: it is retryable (auto-retry + manual),
     * and wiping its rows would force a full re-download after process death.
     *
     * The empty-segment guard ports the old last-good rule: a restored-but-
     * not-yet-resumed task has no live segments; writing an empty row set
     * would destroy its resume state, so the cached (persisted) rows are used.
     */
    private fun captureSnapshots(ids: Collection<Long>): List<DownloadSnapshot> {
        if (!stateLoaded) return emptyList()
        return ids.mapNotNull { id ->
            val item = downloadItems[id] ?: return@mapNotNull null
            when (item.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    val snapshot = engine.snapshotProgress(id, item.status)
                    val effective = if (snapshot.segments.isEmpty()) {
                        restoredSegmentCache[id].orEmpty()
                    } else {
                        restoredSegmentCache[id] = snapshot.segments
                        snapshot.segments
                    }
                    val entity = item.copy().toEntity().copy(
                        downloadedBytes = snapshot.downloadedBytes,
                        totalBytes = snapshot.totalBytes,
                    )
                    DownloadSnapshot(
                        entity,
                        effective.mapNotNull { it.toSegmentEntityOrNull(id) },
                    )
                }
                else -> {
                    restoredSegmentCache.remove(id)
                    DownloadSnapshot(item.copy().toEntity(), emptyList())
                }
            }
        }
    }

    /** Immediate structural write (create / terminal status / pause). */
    private fun persistNow(id: Long) {
        if (!stateLoaded) {
            // Should be rare — command paths await initialisation first. If it
            // ever fires, it points at a lifecycle race worth investigating.
            Log.w(TAG, "Persist requested before state loaded — skipped")
            return
        }
        appScope.launch {
            try {
                store.upsertNow(listOf(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Disk-full/IO errors must not crash the process; the next
                // structural transition re-persists.
                Log.e(TAG, "Structural persist failed for download $id", e)
            }
        }
    }

    /** Restored segment rows for engine [DownloadEngine.restoreTask] calls. */
    private fun restoredSegments(id: Long): List<PersistedSegment> =
        restoredSegmentCache[id] ?: emptyList()

    // ── Legacy JSON migration (one-time) ────────────────────────────────

    private val legacyStateFile = File(context.filesDir, LEGACY_STATE_FILE_NAME)

    /**
     * Imports the retired `download_state.json` (or the even older
     * SharedPreferences document) into Room exactly once. Idempotent: runs
     * only while the downloads table is empty; a kill mid-transaction rolls
     * back and retries on next launch. The legacy file is renamed (never
     * deleted) only after the import verifies.
     */
    private suspend fun importLegacyStateIfNeeded() {
        val legacyJson: String?
        val fromPrefs: Boolean
        val prefsLastId: Long
        when {
            // The marker is set inside the import transaction — authoritative
            // even when the user later deleted every imported row.
            store.legacyImported() -> {
                retireLegacyFile()
                clearLegacyPrefs()
                return
            }
            store.count() != 0 -> {
                // Rows exist without the marker (dev build predating it).
                // Room is authoritative; backfill the marker, retire artifacts.
                store.markLegacyImported()
                retireLegacyFile()
                clearLegacyPrefs()
                return
            }
            legacyStateFile.exists() -> {
                legacyJson = legacyStateFile.readText()
                fromPrefs = false
                prefsLastId = 0L
            }
            else -> {
                val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                legacyJson = prefs.getString(LEGACY_KEY_ITEMS, null)
                fromPrefs = true
                // Pre-1.2.0 releases stored the id sequence in a separate key;
                // the bare-array document carries no sequence of its own.
                prefsLastId = prefs.getLong(LEGACY_KEY_LAST_ID, 0L)
                if (legacyJson == null) {
                    // Fresh/no-legacy state is itself a completed migration.
                    // Persist the decision so a stale artifact can never be
                    // imported after the user later deletes every row.
                    store.markLegacyImported()
                    return
                }
            }
        }

        val state = try {
            LegacyDownloadStateReader.read(legacyJson, fallbackLastId = prefsLastId)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt legacy download state — quarantining", e)
            // Mark before retiring the artifact. If this DB write fails the
            // exception escapes and the artifact remains available for retry.
            store.markLegacyImported()
            if (fromPrefs) {
                // Unparseable prefs content can never be imported — clear it
                // so the startup path stops retrying the same garbage.
                clearLegacyPrefs()
            } else {
                quarantineLegacyFile()
            }
            return
        }
        if (state == null) {
            store.markLegacyImported()
            if (fromPrefs) clearLegacyPrefs() else retireLegacyFile()
            return
        }

        // Validate per-record; one bad entry never aborts the batch. Read
        // non-null-typed fields through nullable locals — Gson documents can
        // carry nulls where Kotlin expects values.
        val validById = state.items
            .filter {
                val url: String? = it.url
                val name: String? = it.fileName
                val path: String? = it.filePath
                it.id > 0 && !url.isNullOrBlank() && !name.isNullOrBlank() && !path.isNullOrBlank()
            }
            .associateBy { it.id }
        val completed = validById.values.filter { it.status == DownloadStatus.COMPLETED }
            .sortedByDescending { it.createdAt }
            .take(DownloadStore.MAX_PERSISTED_COMPLETED)
            .mapTo(HashSet()) { it.id }
        val items = validById.values.filter {
            it.status != DownloadStatus.COMPLETED || it.id in completed
        }.toList()
        val resumableIds = items.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.PAUSED
        }.mapTo(HashSet()) { it.id }

        val imported = try {
            store.importLegacy(
                state.copy(
                    lastId = maxOf(state.lastId, items.maxOfOrNull { it.id } ?: 0L),
                    items = items,
                    segments = state.segments.filterKeys { it in resumableIds },
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unexpected failure rolled the whole transaction back. Keep the
            // artifact for the next attempt but let initialisation continue —
            // escaping into ensureInitialised would disable persistence for
            // the entire process.
            Log.e(TAG, "Legacy import failed — keeping artifact for retry", e)
            return
        }

        if (items.isEmpty()) {
            // Nothing importable (all records malformed) — retrying cannot
            // heal them; retire the artifact.
            Log.w(TAG, "Legacy state held no importable downloads — retiring artifact")
        } else if (imported < items.size) {
            Log.w(TAG, "Imported $imported of ${items.size} legacy download record(s) — malformed entries skipped")
        } else {
            Log.i(TAG, "Imported $imported download record(s) from legacy state")
        }
        if (fromPrefs) clearLegacyPrefs() else retireLegacyFile()
    }

    private fun retireLegacyFile() {
        try {
            if (!legacyStateFile.exists()) return
            val archived = File(legacyStateFile.parentFile, "$LEGACY_STATE_FILE_NAME.imported-${System.currentTimeMillis()}")
            if (legacyStateFile.renameTo(archived)) {
                Log.i(TAG, "Legacy download state archived → ${archived.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to archive legacy download state: ${e.message}")
        }
    }

    private fun quarantineLegacyFile() {
        try {
            if (!legacyStateFile.exists()) return
            val quarantined = File(legacyStateFile.parentFile, "$LEGACY_STATE_FILE_NAME.corrupt-${System.currentTimeMillis()}")
            if (legacyStateFile.renameTo(quarantined)) {
                Log.w(TAG, "Quarantined corrupt legacy state → ${quarantined.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error quarantining legacy state file", e)
        }
    }

    private fun clearLegacyPrefs() {
        try {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit { clear() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear legacy download prefs: ${e.message}")
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
                // The engine is the source of truth. It will mark affected downloads
                // PAUSED + wasWaitingForNetwork and that status transition will render notifications.
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
     *
     * FGS policy: auto-resume can fire from a broadcast context where Android
     * 12+ denies startForeground. Re-promote FIRST — if that fails, the items
     * stay paused (still marked waiting) and are retried on the next network
     * event or a user resume. A "foreground" download must never run without
     * an actual foreground service, or the system kills it.
     */
    private fun handleNetworkAvailable() {
        if (attachedService == null) return

        val waitingDownloads = downloadItems.values.filter {
            it.status == DownloadStatus.PAUSED && it.wasWaitingForNetwork
        }

        if (waitingDownloads.isEmpty()) return

        if (notifManager?.ensureForegroundForActiveWork() != true) {
            Log.w(TAG, "Auto-resume deferred — cannot re-enter foreground from background")
            return
        }

        Log.d(TAG, "Auto-resuming ${waitingDownloads.size} network-paused download(s)")

        waitingDownloads.forEach { item ->
            item.wasWaitingForNetwork = false
            resumeInEngine(item)
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
                        } catch (e: CancellationException) {
                            throw e
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

        /** Retired JSON state file, imported once into Room. */
        private const val LEGACY_STATE_FILE_NAME = "download_state.json"
        private const val LEGACY_PREFS_NAME = "DownloadPrefs"
        private const val LEGACY_KEY_ITEMS = "download_items"
        private const val LEGACY_KEY_LAST_ID = "last_download_id"

        /** Minimum interval between notification updates (ms) to avoid Android rate limiting. */
        private const val NOTIFICATION_THROTTLE_MS = 500L

        /** Minimum interval between UI list emissions — terminal events bypass it. */
        private const val UI_EMIT_THROTTLE_MS = 500L

        /** Statuses that count as "active" for duplicate-URL detection. */
        private val ACTIVE_STATUSES = setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING)

        /** Automatic retry backoff schedule — one attempt per entry. */
        private val AUTO_RETRY_DELAYS_MS = longArrayOf(3_000L, 15_000L, 60_000L)

        /**
         * Failures that retrying cannot fix — the download is left failed for
         * the user immediately instead of burning the retry budget.
         */
        private val NON_RETRYABLE_ERRORS = setOf(
            DownloadTask.ERROR_FILE_MISSING,
            DownloadTask.ERROR_RESUME_URL_DEAD,
            DownloadTask.ERROR_STORAGE_PERMISSION
        )
    }

    private fun safeDownloadedFile(path: String): File? = DownloadDirectory.resolveOwnedFile(path)
}
