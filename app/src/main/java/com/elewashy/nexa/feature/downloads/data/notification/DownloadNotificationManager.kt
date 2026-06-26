package com.elewashy.nexa.feature.downloads.data.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.files.DownloadedFileIntents
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.feature.browser.presentation.MainActivity
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
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

        /** Statuses that show a Cancel action on the notification. */
        private val CANCEL_ACTION_STATUSES = setOf(
            DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED
        )
    }

    /** Whether [service] is currently in the foreground. */
    var isForeground = false
        private set

    private val visibleNotificationIds = mutableSetOf<Int>()
    private val notificationShownAt = mutableMapOf<Int, Long>()

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
            svc.startForeground(SUMMARY_ID, notification)
            isForeground = true
            Log.d(TAG, "Foreground started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground", e)
        }
    }

    // ── Per-download notifications ─────────────────────────────────────

    /**
     * Updates the notification for [item] based on its current status.
     * Also refreshes the summary notification and foreground state.
     */
    fun updateNotification(item: DownloadItem, allItems: Collection<DownloadItem>) {
        val flags = pendingIntentFlags()
        val contentPI = activityPendingIntent(item.id.toInt(), flags)

        when (item.status) {
            DownloadStatus.DOWNLOADING -> {
                postDownloadingNotification(item, contentPI, flags)
                visibleNotificationIds.add(item.id.toInt())
            }

            DownloadStatus.PENDING -> {
                postPendingNotification(item, contentPI, flags)
                visibleNotificationIds.add(item.id.toInt())
            }

            DownloadStatus.PAUSED -> {
                postPausedNotification(item, contentPI, flags)
                visibleNotificationIds.add(item.id.toInt())
            }

            DownloadStatus.COMPLETED -> {
                notificationManager.cancel(item.id.toInt())
                notificationShownAt.remove(item.id.toInt())
                postCompletedNotification(item, flags)
                visibleNotificationIds.add(item.id.toInt())
            }

            DownloadStatus.FAILED -> {
                notificationManager.cancel(item.id.toInt())
                notificationShownAt.remove(item.id.toInt())
                postFailedNotification(item, contentPI, flags)
                visibleNotificationIds.add(item.id.toInt())
            }

            DownloadStatus.CANCELLED -> {
                notificationManager.cancel(item.id.toInt())
                visibleNotificationIds.remove(item.id.toInt())
                notificationShownAt.remove(item.id.toInt())
            }
        }

        updateSummary(allItems)
    }

    /** Cancels the notification for a single download. */
    fun cancelNotification(itemId: Long) {
        notificationManager.cancel(itemId.toInt())
        visibleNotificationIds.remove(itemId.toInt())
        notificationShownAt.remove(itemId.toInt())
    }

    // ── One-shot contextual notifications ──────────────────────────────

    /** Shows notification when download auto-paused after repeated failures. */
    fun showFailurePauseNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.paused_after_repeated_failures))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                service.getString(R.string.download_paused_after_failures_details)
            ))
            .withTimestamp(item.id.toInt())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(activityPendingIntent(item.id.toInt(), flags))
            .addAction(
                R.drawable.ic_stat_resume, service.getString(R.string.resume),
                controlPendingIntent(DownloadService.ACTION_RESUME_DOWNLOAD, item.id, 3000, flags)
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .build()
        notificationManager.notify(item.id.toInt(), notification)
    }

    /** Shows notification when download auto-paused due to network loss. */
    fun showNetworkWaitNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.waiting_for_connection))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                service.getString(R.string.download_waiting_network_details)
            ))
            .withTimestamp(item.id.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(activityPendingIntent(item.id.toInt(), flags))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.PAUSED))
            .build()
        notificationManager.notify(item.id.toInt(), notification)
    }

    /** Brief notification shown when network returns and download auto-resumes. */
    fun showResumeNotification(item: DownloadItem) {
        val flags = pendingIntentFlags()
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_resume)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.resuming_download))
            .withTimestamp(item.id.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(activityPendingIntent(item.id.toInt(), flags))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.DOWNLOADING))
            .build()
        notificationManager.notify(item.id.toInt(), notification)
    }

    // ── Summary & Foreground ──────────────────────────────────────────

    /**
     * Rebuilds the summary notification and manages foreground state.
     * Called after every per-download notification update.
     */
    fun updateSummary(allItems: Collection<DownloadItem>) {
        val hasActive = allItems.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        val hasPaused = allItems.any { it.status == DownloadStatus.PAUSED }
        val needsForeground = hasActive || hasPaused
        pruneDismissedNotifications()
        val totalVisible = visibleNotificationIds.size

        when {
            needsForeground && !isForeground -> {
                val n = buildGroupSummary(isOngoing = true)
                try {
                    service.startForeground(SUMMARY_ID, n)
                    isForeground = true
                    Log.d(TAG, "Foreground started")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting foreground", e)
                }
            }

            !needsForeground && isForeground -> {
                if (totalVisible == 0) {
                    // No visible downloads at all — REMOVE the notification entirely.
                    service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    notificationManager.cancel(SUMMARY_ID)
                    notificationShownAt.remove(SUMMARY_ID)
                } else {
                    // Terminal notifications remain visible — DETACH so they can stand on their own.
                    service.stopForeground(Service.STOP_FOREGROUND_DETACH)
                }
                isForeground = false
                Log.d(TAG, "Foreground stopped")
            }

            needsForeground && isForeground -> {
                notificationManager.notify(
                    SUMMARY_ID,
                    buildGroupSummary(isOngoing = true)
                )
            }
        }

        if (!needsForeground) {
            // Keep the group summary alive as long as there's at least one notification.
            // Canceling the group summary will cause the system to cancel all grouped notifications!
            if (totalVisible > 0) {
                notificationManager.notify(
                    SUMMARY_ID,
                    buildGroupSummary(isOngoing = false)
                )
            } else if (!isForeground) {
                notificationManager.cancel(SUMMARY_ID)
                notificationShownAt.remove(SUMMARY_ID)
            }
        }
    }

    /**
     * Cancels only download-related notifications (per-item + summary).
     */
    fun cancelAllDownloadNotifications(allItemIds: Collection<Long>) {
        allItemIds.forEach { notificationManager.cancel(it.toInt()) }
        notificationManager.cancel(SUMMARY_ID)
        visibleNotificationIds.clear()
        notificationShownAt.clear()
    }

    // ===================================================================
    //  Private notification builders
    // ===================================================================

    private fun postDownloadingNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
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
            .withTimestamp(item.id.toInt())
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

        notificationManager.notify(item.id.toInt(), builder.build())
    }

    private fun postPendingNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.waiting))
            .withTimestamp(item.id.toInt())
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

        notificationManager.notify(item.id.toInt(), builder.build())
    }

    private fun postPausedNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val text = service.getString(R.string.paused_progress, item.progress)
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(item.fileName)
            .setContentText(text)
            .withTimestamp(item.id.toInt())
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

        notificationManager.notify(item.id.toInt(), builder.build())
    }

    private fun postCompletedNotification(item: DownloadItem, flags: Int) {
        val file = File(item.filePath)
        val viewIntent = try {
            DownloadedFileIntents.createViewIntent(service, file, item.mimeType)
        } catch (_: IllegalArgumentException) {
            Intent(service, MainActivity::class.java).apply {
                action = DownloadService.ACTION_OPEN_DOWNLOADS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val viewPI = PendingIntent.getActivity(service, item.id.toInt(), viewIntent, flags)
        val size = LocalizedFormatters.fileSize(service, item.totalBytes)

        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(item.fileName)
            .setContentText(service.getString(R.string.download_complete_size, size))
            .withTimestamp(item.id.toInt())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(viewPI)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setSortKey(getSortKey(DownloadStatus.COMPLETED))
            .build()

        notificationManager.notify(item.id.toInt(), notification)
    }

    private fun postFailedNotification(
        item: DownloadItem, contentPI: PendingIntent, flags: Int
    ) {
        val text = if (item.downloadedBytes > 0) {
            service.getString(R.string.failed_progress, item.progress)
        } else {
            service.getString(R.string.download_failed)
        }

        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(item.fileName)
            .setContentText(text)
            .withTimestamp(item.id.toInt())
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

        notificationManager.notify(item.id.toInt(), notification)
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
            service, downloadId.toInt() + requestCodeOffset, intent, flags
        )
    }
}
