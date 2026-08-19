package com.elewashy.nexa.feature.downloads.data

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.feature.downloads.data.engine.DownloadEngine
import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask
import com.elewashy.nexa.feature.downloads.data.engine.HttpProber
import com.elewashy.nexa.feature.downloads.data.filename.FileNameResolver
import com.elewashy.nexa.feature.downloads.data.notification.DownloadNotificationManager
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadPersistence
import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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
 *  - [DownloadPersistence]  (atomic flat-file JSON snapshot + segment state)
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
     * Automatic retry for FAILED downloads. Transient failures (server
     * hiccups, unreachable resume probes, mid-stream resets) are retried on a
     * backoff schedule before the download is left failed for manual action.
     * Both maps are main-thread only (all mutations happen on Main.immediate).
     */
    private val autoRetryCounts = mutableMapOf<Long, Int>()
    private val autoRetryJobs = mutableMapOf<Long, Job>()

    /**
     * Timestamp of last notification update — throttled to avoid Android's
     * notification rate limit (5/sec). Repository emissions still fire at full rate.
     */
    private var lastNotificationUpdateTime = 0L

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
    private val initGate = CompletableDeferred<Unit>()

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

    @Synchronized
    private fun ensureInitialised() {
        if (initStarted) return
        initStarted = true
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
                    if (attachedService != null && hasActiveWork()) {
                        notifManager?.startForegroundImmediately(attachedService!!)
                    }
                    // Restored records re-reserve their filenames: a restored
                    // item whose .part is gone must not lose its name to a new
                    // download — resuming it later would collide on the .part.
                    downloadItems.values.forEach { item ->
                        if (item.status != DownloadStatus.COMPLETED) {
                            reservedFileNames.add(item.fileName)
                        }
                    }
                    notifManager?.updateSummary(downloadItems.values)
                    startPeriodicFlush()
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
            } catch (e: Exception) {
                // Never fatal: proceed with whatever state loaded; commands must
                // not rethrow initialisation errors onto the app scope.
                Log.e(TAG, "Initialisation failed", e)
            } finally {
                initGate.complete(Unit)
            }
        }
    }

    /** Suspends until persisted state is loaded (see [ensureInitialised]). */
    private suspend fun awaitInitialised() {
        ensureInitialised()
        initGate.await()
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

        // Clean up notifications: only cancel ones that are actively downloading
        // or pending; leave completed, failed, and paused so the user can still
        // interact with them or see them.
        active.forEach {
            notifManager?.cancelNotification(it.id)
            it.status = DownloadStatus.PAUSED // mark them paused internally so the summary matches
        }
        notifManager?.updateSummary(downloadItems.values)

        // Final flush to disk — snapshot items AND segments together here on the
        // main thread so they stay consistent, then do the Gson serialization
        // and disk write on IO (never on the caller/main thread). The app scope
        // outlives the service, so the flush survives onDestroy. Skipped when
        // state never loaded — flushing an empty map would wipe the file.
        if (stateLoaded) {
            val snapshot = takeStateSnapshot()
            appScope.launch(Dispatchers.IO) {
                persistence.forceFlush(snapshot.items, snapshot.segments, snapshot.seq)
            }
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
            notifManager?.showSystemTimeoutPausedNotification(item)
        }
        notifManager?.updateSummary(downloadItems.values)
        persistence.markDirty()
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
            routedResumes.add(item.id)
            return try {
                context.startForegroundService(
                    android.content.Intent(context, DownloadService::class.java)
                        .setAction(DownloadService.ACTION_RESUME_DOWNLOAD)
                        .putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
                )
                true
            } catch (e: Exception) {
                // Android 12+ denies startForegroundService from the background
                // — the item stays FAILED for the next retry or manual action.
                routedResumes.remove(item.id)
                Log.e(TAG, "Failed to start service for resume: ${item.fileName}", e)
                false
            }
        }

        item.failureCount = 0
        item.errorMessage = null

        if (engine.hasTask(item.id)) {
            engine.resume(item.id)
            Log.d(TAG, "Resuming: ${item.fileName}")
        } else {
            // Task not in engine (e.g. restored from persistence) — re-enqueue
            // with its persisted segment state so resume is verified on disk.
            Log.d(TAG, "Re-enqueueing: ${item.fileName}")
            engine.restoreTask(item, persistence.restoredSegments(item.id))
            engine.resume(item.id)
        }
        return true
    }

    override suspend fun retry(id: Long) = resume(id)

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
                persistence.markDirty()
                flushNow()
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
                item.failureCount = 0
                // The final file now guards the name on disk — release the
                // reservation or the set grows with every completed download.
                reservedFileNames.remove(item.fileName)
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
                    notifManager?.cancelNotification(item.id)
                    notifManager?.updateSummary(downloadItems.values)
                    persistence.markDirty()
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
            current.failureCount = 0
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
            val downloadId = persistence.idCounter.incrementAndGet()

            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Nexa"
            ).also { if (!it.exists()) it.mkdirs() }

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
                persistence.markDirty()
                flushNow()
                updateStatus(item, DownloadStatus.FAILED)
                // Release the URL reservation first (the finally would only run
                // afterwards) so stopServiceIfIdle can actually stop the service.
                reservedUrls.remove(url)
                stopServiceIfIdle()
                return
            }

            downloadItems[downloadId] = item
            persistence.markDirty()
            // Persist the new record immediately: a process kill before the
            // first periodic flush would otherwise lose it (and the orphan-.part
            // sweep on next start would delete its in-progress file).
            flushNow()

            // A download is starting while notifications are disabled — without a
            // warning the user would get zero feedback about it. One shot per process.
            maybeEmitNotificationsWarning()

            emit(force = true) // New item — structural change, show it now

            // Enqueue in the custom download engine, reusing the upstream probe so
            // the URL is only hit once.
            engine.enqueue(item, initialProbe = initialProbe)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
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

        if (newStatus == DownloadStatus.COMPLETED || newStatus == DownloadStatus.CANCELLED) {
            item.failureCount = 0
        }

        notifManager?.updateNotification(item, downloadItems.values)
        persistence.markDirty()
        // Terminal states must hit disk immediately — a process kill before
        // the next periodic flush would otherwise lose completions/failures.
        if (newStatus == DownloadStatus.COMPLETED || newStatus == DownloadStatus.FAILED) {
            flushNow()
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
        if (now - lastNotificationUpdateTime >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateTime = now
            notifManager?.updateNotification(item, downloadItems.values)
        }

        persistence.markDirty()
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

    private fun loadDownloadState() {
        val items = persistence.load()
        downloadItems.clear()

        items.forEach { item ->
            downloadItems[item.id] = item

            // Restore task in the engine so it can be resumed. The persisted
            // segment state travels with it so resume is verified on disk instead
            // of assuming bytes [0..downloadedBytes] are contiguous.
            engine.restoreTask(item, persistence.restoredSegments(item.id))
        }

        sweepOrphanPartFiles(items.mapTo(HashSet()) { it.filePath + DownloadTask.PART_SUFFIX })

        Log.d(TAG, "Loaded ${items.size} download items, restored in engine")
    }

    /**
     * Deletes `.part` files that no restored download can resume — leftovers from
     * a process death before the first persistence flush. Without this they would
     * sit in Downloads/Nexa forever.
     */
    private fun sweepOrphanPartFiles(knownPartPaths: Set<String>) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Nexa"
            )
            dir.listFiles { f -> f.isFile && f.name.endsWith(DownloadTask.PART_SUFFIX) }
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
     * Consistent persistence snapshot: items and their segments captured
     * together on the main thread — engine mutations are reposted to main
     * before touching items, so this cannot tear the way copying on IO while
     * engine coroutines mutate the same instances could.
     */
    private data class StateSnapshot(
        val items: List<DownloadItem>,
        val segments: Map<Long, List<PersistedSegment>>,
        /** Capture order — lets persistence drop out-of-order (older) writes. */
        val seq: Long
    )

    /** Main-thread capture counter feeding [StateSnapshot.seq]. */
    private var snapshotSeq = 0L

    private fun takeStateSnapshot(): StateSnapshot {
        // COMPLETED items MUST be persisted — they are the user's download
        // history. CANCELLED never appears here (cancel removes items from the
        // map first). Item copies are captured FIRST and every downstream
        // decision reads the copies: engine tasks mutate live item fields off
        // the main thread, so reading the live map again for the segment pass
        // could observe a status that no longer matches the captured items.
        val items = downloadItems.values
            .filter { it.status != DownloadStatus.CANCELLED }
            .map { it.copy() }
        val segmentEntries = items
            .filter {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
            }
            .map { it.id to engine.snapshotSegments(it.id, it.status) }
        return StateSnapshot(items = items, segments = segmentEntries.toMap(), seq = ++snapshotSeq)
    }

    /**
     * Immediate flush for structural changes (item created, terminal status,
     * removal) — the 2 s periodic flush alone leaves a window where a process
     * kill loses the change. Gated on [stateLoaded] like every other writer.
     */
    private fun flushNow() {
        if (!stateLoaded) {
            // Should be rare — command paths await initialisation first. If it
            // ever fires, it points at a lifecycle race worth investigating.
            Log.w(TAG, "Flush requested before persisted state loaded — skipped")
            return
        }
        val snapshot = takeStateSnapshot()
        appScope.launch(Dispatchers.IO) {
            persistence.forceFlush(snapshot.items, snapshot.segments, snapshot.seq)
        }
    }

    /** Periodically flushes dirty state to disk every 2 seconds. */
    private fun startPeriodicFlush() {
        flushJob = appScope.launch {
            while (isActive) {
                delay(2_000)
                // Never flush before the persisted state has been loaded — the
                // in-memory map is empty until then and would wipe the file.
                if (!stateLoaded) continue
                // Nothing changed since the last write — skip the main-thread
                // snapshot entirely instead of copying the whole list for nothing.
                if (!persistence.isDirty) continue
                // Snapshot atomically on main BEFORE hopping to IO — copying
                // items on IO while engine coroutines mutate them tears reads.
                val snapshot = withContext(Dispatchers.Main.immediate) { takeStateSnapshot() }
                withContext(Dispatchers.IO) {
                    persistence.flushIfDirty(snapshot.items, snapshot.segments, snapshot.seq)
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
            notifManager?.showResumeNotification(item)

            if (engine.hasTask(item.id)) {
                engine.resume(item.id)
            } else {
                engine.restoreTask(item, persistence.restoredSegments(item.id))
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
