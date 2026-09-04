package com.elewashy.nexa.feature.downloads.data.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.core.files.DownloadedFileIntents
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.feature.browser.presentation.MainActivity
import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import java.io.File

/**
 * Single renderer for all download notifications.
 *
 * Notifications are derived only from DownloadItem state. The per-download notification id is a
 * deterministic function of DownloadItem.id, so pause/resume/progress/terminal transitions update
 * the same notification across process recreation instead of allocating process-local ids.
 *
 * All public methods must be called from the main thread.
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
        private const val FALLBACK_ID_BASE = 1_000_000
        private const val FALLBACK_ID_RANGE = Int.MAX_VALUE - FALLBACK_ID_BASE - 1
    }

    private val compatNotificationManager = NotificationManagerCompat.from(service)
    private val visibleChildNotificationIds = mutableSetOf<Int>()
    private val notificationShownAt = mutableMapOf<Int, Long>()
    private var loggedNotificationsDisabled = false

    var isForeground = false
        private set

    private val dataSyncForegroundServiceType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

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

    fun areNotificationsEnabled(): Boolean = compatNotificationManager.areNotificationsEnabled()

    fun startForegroundImmediately(svc: Service) {
        if (isForeground) return
        try {
            ServiceCompat.startForeground(
                svc,
                SUMMARY_ID,
                buildGroupSummary(isOngoing = true),
                dataSyncForegroundServiceType
            )
            isForeground = true
            Log.d(TAG, "Foreground started")
        } catch (e: Exception) {
            isForeground = false
            Log.e(TAG, "Error starting foreground", e)
        }
    }

    fun ensureForegroundForActiveWork(): Boolean {
        if (isForeground) return true
        return try {
            ServiceCompat.startForeground(
                service,
                SUMMARY_ID,
                buildGroupSummary(isOngoing = true),
                dataSyncForegroundServiceType
            )
            isForeground = true
            Log.d(TAG, "Foreground started")
            true
        } catch (e: Exception) {
            isForeground = false
            Log.e(TAG, "Error entering foreground", e)
            false
        }
    }

    fun demoteFromForeground() {
        if (!isForeground) return
        try {
            service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching foreground: ${e.message}")
        }
        isForeground = false
        Log.d(TAG, "Foreground detached")
    }

    /** Renders all non-completed restored items (for service/process recreation). */
    fun syncNotifications(allItems: Collection<DownloadItem>) {
        allItems
            .filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.CANCELLED }
            .forEach { renderChild(it) }
        updateSummary(allItems)
    }

    fun updateNotification(item: DownloadItem, allItems: Collection<DownloadItem>) {
        renderChild(item)
        updateSummary(allItems)
    }

    fun cancelNotification(itemId: Long) {
        val id = notificationId(itemId)
        notificationManager.cancel(id)
        visibleChildNotificationIds.remove(id)
        notificationShownAt.remove(id)
    }

    fun cancelAllDownloadNotifications(allItemIds: Collection<Long>) {
        allItemIds.forEach { cancelNotification(it) }
        notificationManager.cancel(SUMMARY_ID)
        visibleChildNotificationIds.clear()
        notificationShownAt.clear()
    }

    /** Compatibility wrappers retained for callers; they do not create separate transient state. */
    fun showNetworkWaitNotification(item: DownloadItem) = renderChild(item)
    fun showResumeNotification(item: DownloadItem) = renderChild(item)
    fun showSystemTimeoutPausedNotification(item: DownloadItem) = renderChild(item)

    private fun renderChild(item: DownloadItem) {
        val id = notificationId(item.id)
        when (item.status) {
            DownloadStatus.PENDING -> post(id, buildPending(item, id))
            DownloadStatus.DOWNLOADING -> post(id, buildDownloading(item, id))
            DownloadStatus.PAUSED -> post(id, buildPaused(item, id))
            DownloadStatus.FAILED -> post(id, buildFailed(item, id))
            DownloadStatus.COMPLETED -> post(id, buildCompleted(item, id))
            DownloadStatus.CANCELLED -> {
                notificationManager.cancel(id)
                visibleChildNotificationIds.remove(id)
                notificationShownAt.remove(id)
            }
        }
    }

    fun updateSummary(allItems: Collection<DownloadItem>) {
        pruneDismissedNotifications()
        val hasActive = allItems.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        val hasWaitingForNetwork = allItems.any { it.status == DownloadStatus.PAUSED && it.wasWaitingForNetwork }
        val shouldHoldForeground = hasActive || hasWaitingForNetwork

        when {
            shouldHoldForeground && !isForeground -> ensureForegroundForActiveWork()
            !shouldHoldForeground && isForeground -> demoteFromForeground()
        }

        pruneRemovedChildren(allItems)

        val hasAnyVisibleChild = visibleChildNotificationIds.isNotEmpty()
        if (shouldHoldForeground || hasAnyVisibleChild) {
            safeNotify(SUMMARY_ID, buildGroupSummary(isOngoing = isForeground))
        } else {
            notificationManager.cancel(SUMMARY_ID)
            notificationShownAt.remove(SUMMARY_ID)
        }
    }

    private fun post(id: Int, notification: Notification) {
        if (safeNotify(id, notification)) {
            visibleChildNotificationIds.add(id)
        }
    }

    private fun safeNotify(id: Int, notification: Notification): Boolean {
        if (!compatNotificationManager.areNotificationsEnabled()) {
            if (!loggedNotificationsDisabled) {
                loggedNotificationsDisabled = true
                Log.w(TAG, "Notifications are disabled — skipping download notifications")
            }
            return false
        }
        return try {
            notificationManager.notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied while posting $id")
            false
        }
    }

    private fun buildDownloading(item: DownloadItem, id: Int): Notification {
        val progress = item.progress
        val totalSize = if (item.totalBytes > 0) LocalizedFormatters.fileSize(service, item.totalBytes) else service.getString(R.string.unknown)
        val downloadedSize = LocalizedFormatters.fileSize(service, item.downloadedBytes)
        val speed = LocalizedFormatters.speed(service, item.downloadSpeedBytesPerSecond)
        val base = service.getString(R.string.download_progress_size, downloadedSize, totalSize)
        val text = if (speed.isNotEmpty()) "$base  $speed" else base

        return baseBuilder(item, id, R.drawable.ic_stat_download)
            .setContentText(text)
            .setSubText(formatNotificationEta(item))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, progress, item.totalBytes <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSortKey(getSortKey(DownloadStatus.DOWNLOADING))
            .addAction(
                R.drawable.ic_stat_pause,
                service.getString(R.string.pause),
                controlPendingIntent(DownloadService.ACTION_PAUSE_DOWNLOAD, item.id)
            )
            .addAction(
                R.drawable.ic_stat_cancel,
                service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id)
            )
            .build()
    }

    private fun buildPending(item: DownloadItem, id: Int): Notification =
        baseBuilder(item, id, R.drawable.ic_stat_download)
            .setContentText(service.getString(R.string.waiting))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSortKey(getSortKey(DownloadStatus.PENDING))
            .addAction(
                R.drawable.ic_stat_cancel,
                service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id)
            )
            .build()

    private fun buildPaused(item: DownloadItem, id: Int): Notification {
        val text = if (item.wasWaitingForNetwork) {
            service.getString(R.string.waiting_for_connection)
        } else {
            service.getString(R.string.paused_progress, item.progress)
        }
        val builder = baseBuilder(item, id, R.drawable.ic_stat_pause)
            .setContentText(text)
            .setProgress(100, item.progress, false)
            .setOngoing(item.wasWaitingForNetwork)
            .setSilent(!item.wasWaitingForNetwork)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .addAction(
                R.drawable.ic_stat_resume,
                service.getString(R.string.resume),
                controlPendingIntent(DownloadService.ACTION_RESUME_DOWNLOAD, item.id)
            )
            .addAction(
                R.drawable.ic_stat_cancel,
                service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id)
            )
        if (item.wasWaitingForNetwork) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(service.getString(R.string.download_waiting_network_details)))
        }
        return builder.build()
    }

    private fun buildCompleted(item: DownloadItem, id: Int): Notification {
        val file = File(item.filePath)
        val viewIntent = try {
            DownloadedFileIntents.createViewIntent(service, file, item.mimeType)
        } catch (_: IllegalArgumentException) {
            Intent(service, MainActivity::class.java).apply {
                action = DownloadService.ACTION_OPEN_DOWNLOADS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val size = LocalizedFormatters.fileSize(service, item.totalBytes)
        return baseBuilder(item, id, R.drawable.ic_stat_check)
            .setContentText(service.getString(R.string.download_complete_size, size))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(PendingIntent.getActivity(service, requestCode("open", item.id), viewIntent, pendingIntentFlags()))
            .setAutoCancel(true)
            .setSortKey(getSortKey(DownloadStatus.COMPLETED))
            .build()
    }

    private fun buildFailed(item: DownloadItem, id: Int): Notification {
        val text = resolveErrorMessage(item.errorMessage)
            ?: if (item.downloadedBytes > 0) service.getString(R.string.failed_progress, item.progress) else service.getString(R.string.download_failed)
        return baseBuilder(item, id, R.drawable.ic_stat_error)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setSortKey(getSortKey(DownloadStatus.FAILED))
            .addAction(
                R.drawable.ic_stat_resume,
                service.getString(R.string.retry),
                controlPendingIntent(DownloadService.ACTION_RETRY_DOWNLOAD, item.id)
            )
            .addAction(
                R.drawable.ic_stat_cancel,
                service.getString(R.string.cancel),
                controlPendingIntent(DownloadService.ACTION_CANCEL_DOWNLOAD, item.id)
            )
            .build()
    }

    private fun baseBuilder(item: DownloadItem, id: Int, icon: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(item.fileName)
            .withTimestamp(id)
            .setContentIntent(activityPendingIntent(id))
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun buildGroupSummary(isOngoing: Boolean): Notification {
        val text = if (isOngoing) service.getString(R.string.downloads_in_progress) else service.getString(R.string.downloads)
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(text)
            .withTimestamp(SUMMARY_ID)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(activityPendingIntent(SUMMARY_ID))
            .setAutoCancel(!isOngoing)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun resolveErrorMessage(errorMessage: String?): String? = when (errorMessage) {
        null -> null
        DownloadTask.ERROR_FINALIZE_FAILED -> service.getString(R.string.download_finalize_failed)
        DownloadTask.ERROR_FILE_MISSING -> service.getString(R.string.downloaded_file_missing)
        DownloadTask.ERROR_RESUME_UNREACHABLE -> service.getString(R.string.download_resume_unreachable)
        DownloadTask.ERROR_RESUME_URL_DEAD -> service.getString(R.string.download_resume_url_dead)
        DownloadTask.ERROR_STORAGE_PERMISSION -> service.getString(R.string.storage_permission_required_downloads)
        else -> errorMessage
    }

    private fun pruneDismissedNotifications() {
        val activeIds = notificationManager.activeNotifications.map { it.id }.toSet()
        visibleChildNotificationIds.retainAll(activeIds)
        notificationShownAt.keys.retainAll(activeIds + SUMMARY_ID)
    }

    private fun pruneRemovedChildren(allItems: Collection<DownloadItem>) {
        val validIds = allItems
            .filter { it.status != DownloadStatus.CANCELLED }
            .mapTo(HashSet()) { notificationId(it.id) }
        visibleChildNotificationIds.filter { it !in validIds }.forEach { id ->
            notificationManager.cancel(id)
            notificationShownAt.remove(id)
        }
        visibleChildNotificationIds.retainAll(validIds)
    }

    private fun NotificationCompat.Builder.withTimestamp(notificationId: Int): NotificationCompat.Builder =
        setShowWhen(true).setWhen(notificationShownAt.getOrPut(notificationId) { System.currentTimeMillis() })

    private fun formatNotificationEta(item: DownloadItem): String? =
        if (item.status == DownloadStatus.DOWNLOADING && item.etaSeconds >= 0) {
            LocalizedFormatters.eta(service, item.etaSeconds)
        } else null

    private fun getSortKey(status: DownloadStatus): String = when (status) {
        DownloadStatus.DOWNLOADING -> "1_active"
        DownloadStatus.PENDING -> "2_pending"
        DownloadStatus.PAUSED -> "3_paused"
        DownloadStatus.COMPLETED -> "4_completed"
        DownloadStatus.FAILED -> "5_failed"
        else -> "9_other"
    }

    private fun notificationId(itemId: Long): Int {
        // Historical ids are epoch-seconds/counter values and fit in positive Int range. Keep them
        // exact so notifications and actions survive process recreation. Very large future ids fall
        // back to a deterministic positive range reserved away from SUMMARY_ID.
        if (itemId in 1 until Int.MAX_VALUE && itemId.toInt() != SUMMARY_ID) return itemId.toInt()
        var id = FALLBACK_ID_BASE + ((itemId.hashCode() and 0x7FFFFFFF) % FALLBACK_ID_RANGE)
        if (id == SUMMARY_ID) id++
        return id
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun activityPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(service, MainActivity::class.java).apply {
            action = DownloadService.ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(service, requestCode, intent, pendingIntentFlags())
    }

    private fun controlPendingIntent(action: String, downloadId: Long): PendingIntent =
        PendingIntent.getService(
            service,
            requestCode(action, downloadId),
            DownloadService.createControlIntent(service, action, downloadId),
            pendingIntentFlags()
        )

    private fun requestCode(namespace: String, downloadId: Long): Int =
        ((31 * notificationId(downloadId)) + namespace.hashCode()) and 0x7FFFFFFF
}
