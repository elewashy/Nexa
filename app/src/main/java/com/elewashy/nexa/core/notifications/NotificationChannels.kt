package com.elewashy.nexa.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager

object NotificationChannels {
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_HIGH = 4

    const val DOWNLOADS = "download_channel"
    const val ADBLOCK = "adblock_channel"
    const val YOUTUBE_CONVERSION = "youtube_conversion"

    /**
     * Creates a channel idempotently. Channels are created lazily by each
     * consumer at first use — never eagerly at app start — so the channel
     * name is resolved with the locale active at that moment (Android never
     * renames an existing channel).
     */
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
