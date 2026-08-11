package com.elewashy.nexa.feature.downloads.data.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.files.DownloadedFileIntents
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.feature.browser.presentation.MainActivity
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import java.io.File

/**
 * Manages all download notifications: per-download progress, summary/group,
 * foreground-service lifecycle, and one-shot informational notifications.
 *
 * All public methods must be called from the **main thread**.
 */
class DownloadNotificationManager(
    private val service: Service,
    private val notificationManager: NotificationManager
) {

    companion object {
        private const val TAG = "DlNotifManager"
        private const val CHANNEL_ID = NotificationChannels.DOWNLOADS
        private const val SUMMARY_ID = 999999
        private const val GROUP_KEY = "com.elewashy.nexa.DOWNLOADS"

        /**
         * Notification codes are derived from item ids via hash into this range.
         * Item ids are epoch-seconds (~1.7e9 and growing), which would overflow
         * Int and collide with [SUMMARY_ID] — hashing keeps codes bounded and
         * stable per active item.
         */
        private const val CODE_RANGE = 900_000

        /** Statuses that show a Cancel action on the notification. */
        private val CANCEL_ACTION_STATUSES = setOf(
            DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED
        )
    }

    /** Whether [service] is currently in the foreground. */
    var isForeground = false
        private set

    private val compatNotificationManager = NotificationManagerCompat.from(service)

    /** item id → stable bounded notification code, collision-safe per active set. */
    private val itemNotificationCodes = LinkedHashMap<Long, Int>()
    private val usedNotificationCodes = mutableSetOf<Int>()

    private val visibleNotificationIds = mutableSetOf<Int>()
    private val notificationShownAt = mutableMapOf<Int, Long>()

    /** Logged once per process so disabled-notification skips don't spam logcat. */
    private var loggedNotificationsDisabled = false

    // ── Channel ────────────────────────────────────────────────────────

    /**
     * Creates the notification channel.
     * Safe to call multiple times — Android ignores duplicate channel creation.
     */
    fun createChannel() {
        NotificationChannels.ensure(
            notificationManager = notificationManager,
            id = CHANNEL_ID,
            name = service.getString(R.string.download_notification_channel_name),
            importance = NotificationChannels.IMPORTANCE_LOW,
            description = service.getString(R.string.download_notification_channel_description),
            showBadge = false
        )
    }

    /** Whether the user allows this app to post notifications at all. */
    fun areNotificationsEnabled(): Boolean = compatNotificationManager.areNotificationsEnabled()

    /**
     * Immediately starts the service in foreground mode with a minimal notification.
     * Must be called within 5 seconds of `startService()` on Android 12+.
     */
    fun startForegroundImmediately(svc: Service) {
        if (isForeground) return

        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(service.getString(R.string.preparing_downloads))
            .setContentText(service.getString(R.string.app_name))
            .withTimestamp(SUMMARY_ID)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        try {
            ServiceCompat.startForeground(
                svc, SUMMARY_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            isForeground = true
            Log.d(TAG, "Foreground started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground", e)
        }
    }

    /**
     * Demotes the service out of the foreground (removing the foreground
     * notification) without touching per-item notifications. Used when the
     * system enforces the dataSync foreground-service timeout.
     */
    fun demoteFromForeground() {
        if (!isForeground) return
        try {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground: ${e.message}")
        }
        isForeground = false
        notificationShownAt.remove(SUMMARY_ID)
        Log.d(TAG, "Foreground demoted (timeout)")
    }

    /**
     * Ensures the service holds a dataSync foreground service for active work.
     * Returns `true` when the FGS is established (or already held), `false`
     * when the system denied the promotion — on Android 12+ background FGS
     * starts throw ForegroundServiceStartNotAllowedException. Callers must not
     * run a download without the FGS: it would be killed, so keep the item
     * paused and retry later instead.
     */
    fun ensureForegroundForActiveWork(): Boolean {
        if (isForeground) return true
        val n = buildGroupSummary(isOngoing = true)
        return try {
            ServiceCompat.startForeground(
                service, SUMMARY_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            isForeground = true
            Log.d(TAG, "Foreground started (re-promotion)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error re-entering foreground", e)
            isForeground = false
            false
        }
    }

    // ── Per-download notifications ─────────────────────────────────────

    /**
     * Updates the notification for [item] based on its current status.
     * Also refreshes the summary notification and foreground state.
     */
    fun updateNotification(item: DownloadItem, allItems: Collection<DownloadItem>) {
        val flags = pendingIntentFlags()
        val code = notificationCode(item.id)
        val contentPI = activityPendingIntent(code, flags)

        when (item.status) {
            DownloadStatus.DOWNLOADING -> {
                postDownloadingNotification(item, contentPI, flags)
                visibleNotificationIds.add(code)
            }

            DownloadStatus.PENDING -> {
                postPendingNotification(item, contentPI, flags)
                visibleNotificationIds.add(code)
            }

            DownloadStatus.PAUSED -> {
                postPausedNotification(item, contentPI, flags)
                visibleNotificationIds.add(code)
            }

            DownloadStatus.COMPLETED -> {
                notificationManager.cancel(code)
                notificationShownAt.remove(code)
                postCompletedNotification(item, flags)
                visibleNotificationIds.add(code)
            }

            DownloadStatus.FAILED -> {
                notificationManager.cancel(code)
                notificationShownAt.remove(code)
                postFailedNotification(item, contentPI, flags)
                visibleNotificationIds.add(code)
            }

            DownloadStatus.CANCELLED -> {
                notificationManager.cancel(code)
                visibleNotificationIds.remove(code)
                notificationShownAt.remove(code)
            }
        }

        updateSummary(allItems)
    }

    /** Cancels the notification for a single download. */
    fun cancelNotification(itemId: Long) {
        val code = itemNotificationCodes[itemId]
            ?: (itemId.hashCode() and 0x7FFFFFFF) % CODE_RANGE
        notificationManager.cancel(code)
        visibleNotificationIds.remove(code)
        notificationShownAt.remove(code)
        releaseNotificationCode(itemId)
    }

    // ── One-shot contextual notifications ──────────────────────────────

    /** Shows notification when download auto-paused after repeated failures. */
    fun showFailurePauseNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val code = notificationCode(item.id)
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.paused_after_repeated_failures))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                service.getString(R.string.download_paused_after_failures_details)
            ))
            .withTimestamp(code)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(activityPendingIntent(code, flags))
            .addAction(
                R.drawable.ic_stat_resume, service.getString(R.string.resume),
                controlPendingIntent(DownloadService.ACTION_RESUME_DOWNLOAD, item.id, 3000, flags)
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .build()
        safeNotify(code, notification)
    }

    /** Shows notification when download auto-paused due to network loss. */
    fun showNetworkWaitNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val code = notificationCode(item.id)
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.waiting_for_connection))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                service.getString(R.string.download_waiting_network_details)
            ))
            .withTimestamp(code)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(activityPendingIntent(code, flags))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .build()
        safeNotify(code, notification)
    }

    /** Brief notification shown when network returns and download auto-resumes. */
    fun showResumeNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val code = notificationCode(item.id)
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_resume)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.resuming_download))
            .withTimestamp(code)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(activityPendingIntent(code, flags))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.DOWNLOADING))
            .build()
        safeNotify(code, notification)
    }

    /** Shown when the system forced a pause via the dataSync FGS timeout. */
    fun showSystemTimeoutPausedNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val code = notificationCode(item.id)
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.download_paused_system_timeout))
            .withTimestamp(code)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(activityPendingIntent(code, flags))
            .addAction(
                R.drawable.ic_stat_resume, service.getString(R.string.resume),
                controlPendingIntent(DownloadService.ACTION_RESUME_DOWNLOAD, item.id, 3000, flags)
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .build()
        safeNotify(code, notification)
    }

    // ── Summary & Foreground ──────────────────────────────────────────

    /**
     * Rebuilds the summary notification and manages foreground state.
     * Called after every per-download notification update.
     *
     * Foreground is held only while work is ACTIVE. Paused items keep regular
     * (non-foreground) notifications — holding a dataSync FGS for paused work
     * wastes the system's foreground-service quota.
     */
    fun updateSummary(allItems: Collection<DownloadItem>) {
        val hasActive = allItems.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        val hasPaused = allItems.any { it.status == DownloadStatus.PAUSED }
        val hasWaitingForNetwork = allItems.any { it.status == DownloadStatus.PAUSED && it.wasWaitingForNetwork }
        val needsForeground = hasActive
        pruneStaleNotificationCodes(allItems)
        pruneDismissedNotifications()
        val totalVisible = visibleNotificationIds.size

        when {
            needsForeground && !isForeground -> {
                // Promotion can fail on S+ (background FGS start denied) — the
                // flag must only be set when the FGS is actually held.
                ensureForegroundForActiveWork()
            }

            !needsForeground && isForeground -> {
                when {
                    hasWaitingForNetwork -> {
                        // Auto-resume cannot re-enter foreground from a network
                        // callback on S+ (ForegroundServiceStartNotAllowedException),
                        // so hold the FGS until the waiting items resume — demoting
                        // here would strand them running without any FGS.
                        safeNotify(SUMMARY_ID, buildGroupSummary(isOngoing = true))
                    }
                    hasPaused -> {
                        // Paused-only work: remove the foreground notification.
                        // Removing the group summary makes the system cancel every
                        // grouped child, so re-post the summary and all paused
                        // children afterwards — otherwise their Paused/Resume
                        // notifications vanish.
                        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        isForeground = false
                        notificationShownAt.remove(SUMMARY_ID)
                        repostGroupChildrenAfterDemotion(allItems)
                        Log.d(TAG, "Foreground stopped")
                        return
                    }
                    totalVisible == 0 -> {
                        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        notificationManager.cancel(SUMMARY_ID)
                        notificationShownAt.remove(SUMMARY_ID)
                    }
                    else -> {
                        // Terminal notifications remain visible — DETACH so they can stand on their own.
                        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
                    }
                }
                if (!hasWaitingForNetwork) {
                    isForeground = false
                    Log.d(TAG, "Foreground stopped")
                }
            }

            needsForeground && isForeground -> {
                safeNotify(SUMMARY_ID, buildGroupSummary(isOngoing = true))
            }
        }

        if (!needsForeground) {
            // Keep the group summary alive as long as there's at least one notification.
            // Canceling the group summary will cause the system to cancel all grouped notifications!
            if (totalVisible > 0) {
                // isForeground is only true here while holding the FGS for
                // network-waiting items — that summary must stay ongoing.
                safeNotify(SUMMARY_ID, buildGroupSummary(isOngoing = isForeground))
            } else if (!isForeground) {
                notificationManager.cancel(SUMMARY_ID)
                notificationShownAt.remove(SUMMARY_ID)
            }
        }
    }

    /**
     * Re-posts the group summary and every paused child after a
     * STOP_FOREGROUND_REMOVE demotion — removing the foreground group summary
     * makes the system cancel all grouped notifications, which would drop the
     * per-item Paused notifications the user needs to resume.
     */
    private fun repostGroupChildrenAfterDemotion(allItems: Collection<DownloadItem>) {
        safeNotify(SUMMARY_ID, buildGroupSummary(isOngoing = false))
        val flags = pendingIntentFlags()
        allItems.filter { it.status == DownloadStatus.PAUSED }.forEach { item ->
            val code = notificationCode(item.id)
            postPausedNotification(item, activityPendingIntent(code, flags), flags)
            visibleNotificationIds.add(code)
        }
    }

    /**
     * Cancels only download-related notifications (per-item + summary).
     */
    fun cancelAllDownloadNotifications(allItemIds: Collection<Long>) {
        allItemIds.forEach { cancelNotification(it) }
        notificationManager.cancel(SUMMARY_ID)
        visibleNotificationIds.clear()
        notificationShownAt.clear()
    }

    // ===================================================================
    //  Notification code allocation
    // ===================================================================

    /**
     * Maps an item id to a stable, bounded notification code. Codes are unique
     * across the active set (linear probing on hash collisions).
     */
    private fun notificationCode(itemId: Long): Int {
        itemNotificationCodes[itemId]?.let { return it }

        var code = (itemId.hashCode() and 0x7FFFFFFF) % CODE_RANGE
        while (code == SUMMARY_ID || code in usedNotificationCodes) {
            code = (code + 1) % CODE_RANGE
        }
        usedNotificationCodes.add(code)
        itemNotificationCodes[itemId] = code
        return code
    }

    private fun releaseNotificationCode(itemId: Long) {
        itemNotificationCodes.remove(itemId)?.let { usedNotificationCodes.remove(it) }
    }

    /** Frees codes of items that left the list so the bounded range never leaks. */
    private fun pruneStaleNotificationCodes(allItems: Collection<DownloadItem>) {
        val knownIds = allItems.mapTo(HashSet()) { it.id }
        itemNotificationCodes.keys
            .filter { it !in knownIds }
            .forEach { releaseNotificationCode(it) }
    }

    // ===================================================================
    //  Private notification builders
    // ===================================================================

    /**
     * Posts a notification only when the user allows them. Posting while
     * POST_NOTIFICATIONS is denied can throw on Android 13+ and is wasted work
     * otherwise.
     */
    private fun safeNotify(id: Int, notification: Notification) {
        if (!compatNotificationManager.areNotificationsEnabled()) {
            if (!loggedNotificationsDisabled) {
                loggedNotificationsDisabled = true
                Log.w(TAG, "Notifications are disabled — skipping download notifications")
            }
            return
        }
        notificationManager.notify(id, notification)
    }

    private fun postDownloadingNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val code = notificationCode(item.id)
        val progress = item.progress
        val totalSize = if (item.totalBytes > 0)
            LocalizedFormatters.fileSize(service, item.totalBytes)
        else service.getString(R.string.unknown)
        val downloadedSize = LocalizedFormatters.fileSize(service, item.downloadedBytes)

        val statusParts = mutableListOf<String>()
        val speed = LocalizedFormatters.speed(service, item.downloadSpeedBytesPerSecond)
        if (speed.isNotEmpty()) statusParts.add(speed)
        val headerEta = formatNotificationEta(item)

        val text = if (statusParts.isNotEmpty()) {
            "${service.getString(R.string.download_progress_size, downloadedSize, totalSize)}  ${statusParts.joinToString(" - ")}"
        } else {
            service.getString(R.string.download_progress_size, downloadedSize, totalSize)
        }

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText(text)
            .setSubText(headerEta)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .withTimestamp(code)
            .setProgress(100, progress, item.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.DOWNLOADING))
            .setContentIntent(contentPI)
            .addAction(
                R.drawable.ic_stat_pause, service.getString(R.string.pause),
                controlPendingIntent(DownloadService.ACTION_PAUSE_DOWNLOAD, item.id, 2000, flags)
            )
            .addAction(
                R.drawable.ic_stat_cancel, service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id, 1000, flags)
            )

        safeNotify(code, builder.build())
    }

    private fun postPendingNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val code = notificationCode(item.id)
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.waiting))
            .withTimestamp(code)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PENDING))
            .setContentIntent(contentPI)
            .addAction(
                R.drawable.ic_stat_cancel, service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id, 1000, flags)
            )

        safeNotify(code, builder.build())
    }

    private fun postPausedNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val code = notificationCode(item.id)
        val text = service.getString(R.string.paused_progress, item.progress)
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(text)
            .withTimestamp(code)
            .setProgress(100, item.progress, false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .setContentIntent(contentPI)
            .addAction(
                R.drawable.ic_stat_resume, service.getString(R.string.resume),
                controlPendingIntent(DownloadService.ACTION_RESUME_DOWNLOAD, item.id, 3000, flags)
            )
            .addAction(
                R.drawable.ic_stat_cancel, service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id, 1000, flags)
            )

        safeNotify(code, builder.build())
    }

    private fun postCompletedNotification(item: DownloadItem, flags: Int) {
        val code = notificationCode(item.id)
        val file = File(item.filePath)
        val viewIntent = try {
            DownloadedFileIntents.createViewIntent(service, file, item.mimeType)
        } catch (_: IllegalArgumentException) {
            Intent(service, MainActivity::class.java).apply {
                action = DownloadService.ACTION_OPEN_DOWNLOADS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val viewPI = PendingIntent.getActivity(service, code, viewIntent, flags)
        val size = LocalizedFormatters.fileSize(service, item.totalBytes)

        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.download_complete_size, size))
            .withTimestamp(code)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(viewPI)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.COMPLETED))
            .build()

        safeNotify(code, notification)
    }

    private fun postFailedNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val code = notificationCode(item.id)
        val text = resolveErrorMessage(item.errorMessage)
            ?: if (item.downloadedBytes > 0) {
                service.getString(R.string.failed_progress, item.progress)
            } else {
                service.getString(R.string.download_failed)
            }

        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(item.fileName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .withTimestamp(code)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPI)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.FAILED))
            .addAction(
                R.drawable.ic_stat_resume, service.getString(R.string.retry),
                controlPendingIntent(DownloadService.ACTION_RESUME_DOWNLOAD, item.id, 3000, flags)
            )
            .addAction(
                R.drawable.ic_stat_cancel, service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id, 1000, flags)
            )
            .build()

        safeNotify(code, notification)
    }

    /**
     * [DownloadItem.errorMessage] carries either an already-localized message
     * (set by the repository layer, which holds a Context) or a
     * locale-independent sentinel from the engine ([DownloadTask] has no
     * Context) — sentinels are resolved here, the only display site.
     */
    private fun resolveErrorMessage(errorMessage: String?): String? =
        when (errorMessage) {
            null -> null
            DownloadTask.ERROR_FINALIZE_FAILED ->
                service.getString(R.string.download_finalize_failed)
            DownloadTask.ERROR_FILE_MISSING ->
                service.getString(R.string.downloaded_file_missing)
            else -> errorMessage
        }

    // ── Summary builder ───────────────────────────────────────────────

    private fun buildGroupSummary(isOngoing: Boolean): Notification {
        val title = service.getString(R.string.app_name)
        val text = if (isOngoing) service.getString(R.string.downloads_in_progress) else service.getString(R.string.downloads)

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .withTimestamp(SUMMARY_ID)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(activityPendingIntent(SUMMARY_ID, pendingIntentFlags()))
            .setAutoCancel(!isOngoing)
            .setOngoing(isOngoing)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun pruneDismissedNotifications() {
        val activeIds = notificationManager.activeNotifications
            .map { it.id }
            .toSet()
        visibleNotificationIds.retainAll(activeIds)
        notificationShownAt.keys.retainAll(activeIds + SUMMARY_ID)
    }

    private fun NotificationCompat.Builder.withTimestamp(notificationId: Int): NotificationCompat.Builder {
        return setShowWhen(true)
            .setWhen(notificationTimestamp(notificationId))
    }

    private fun notificationTimestamp(notificationId: Int): Long {
        return notificationShownAt.getOrPut(notificationId) { System.currentTimeMillis() }
    }

    private fun formatNotificationEta(item: DownloadItem): String? {
        if (item.status != DownloadStatus.DOWNLOADING || item.etaSeconds < 0) return null
        return LocalizedFormatters.eta(service, item.etaSeconds)
    }

    private fun getSortKey(status: DownloadStatus): String {
        return when (status) {
            DownloadStatus.DOWNLOADING -> "1_active"
            DownloadStatus.PENDING -> "2_pending"
            DownloadStatus.PAUSED -> "3_paused"
            DownloadStatus.COMPLETED -> "4_completed"
            DownloadStatus.FAILED -> "5_failed"
            else -> "9_other"
        }
    }

    // ── PendingIntent helpers ─────────────────────────────────────────

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun activityPendingIntent(requestCode: Int, flags: Int): PendingIntent {
        val intent = Intent(service, MainActivity::class.java).apply {
            action = DownloadService.ACTION_OPEN_DOWNLOADS
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(service, requestCode, intent, flags)
    }

    private fun controlPendingIntent(
        action: String, downloadId: Long, requestCodeOffset: Int, flags: Int
    ): PendingIntent {
        val intent = DownloadService.createControlIntent(service, action, downloadId)
        return PendingIntent.getService(
            service, notificationCode(downloadId) + requestCodeOffset, intent, flags
        )
    }
}
