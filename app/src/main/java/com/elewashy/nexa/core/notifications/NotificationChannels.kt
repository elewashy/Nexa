package com.elewashy.nexa.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_HIGH = 4

    const val DOWNLOADS = "download_channel"
    const val ADBLOCK = "adblock_channel"
    const val YOUTUBE_CONVERSION = "youtube_conversion"

    fun ensureCoreChannels(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensure(
            notificationManager = notificationManager,
            id = DOWNLOADS,
            name = "Downloads",
            importance = IMPORTANCE_LOW,
            description = "Download progress and status",
            showBadge = false,
        )
        ensure(
            notificationManager = notificationManager,
            id = ADBLOCK,
            name = "AdBlock Updates",
            importance = IMPORTANCE_LOW,
            description = "Notifications for AdBlock list updates",
            showBadge = false,
        )
        ensure(
            notificationManager = notificationManager,
            id = YOUTUBE_CONVERSION,
            name = "YouTube Conversion",
            importance = IMPORTANCE_LOW,
            description = "YouTube video conversion progress",
            showBadge = false,
        )
    }

    fun ensure(
        notificationManager: NotificationManager,
        id: String,
        name: String,
        importance: Int,
        description: String,
        showBadge: Boolean = true,
        enableLights: Boolean = false,
        enableVibration: Boolean = false
    ) {
        val channel = NotificationChannel(id, name, importance).apply {
            this.description = description
            setShowBadge(showBadge)
            enableLights(enableLights)
            enableVibration(enableVibration)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
