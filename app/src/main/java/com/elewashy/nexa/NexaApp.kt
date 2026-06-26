package com.elewashy.nexa

import android.app.Application
import com.elewashy.nexa.core.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to trigger Hilt's code generation, including a
 * base class that uses as the application-level dependency container.
 *
 * Hosts the Hilt singleton component. UI theming is Compose-owned and read by
 * each Activity through [com.elewashy.nexa.ui.theme.NexaTheme].
 */
@HiltAndroidApp
class NexaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCoreChannels(this)
    }
}
