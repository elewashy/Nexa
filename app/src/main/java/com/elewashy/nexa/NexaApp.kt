package com.elewashy.nexa

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to trigger Hilt's code generation, including a
 * base class that uses as the application-level dependency container.
 *
 * Hosts the Hilt singleton component. UI theming is Compose-owned and read by
 * each Activity through [com.elewashy.nexa.ui.theme.NexaTheme].
 *
 * Notification channels are NOT created here: each consumer creates its own
 * channel lazily at first use so names resolve in the active locale.
 */
@HiltAndroidApp
class NexaApp : Application()
