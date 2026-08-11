package com.elewashy.nexa

import android.app.Application
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.downloads.data.filename.FileNameResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

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
class NexaApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NetworkDependencies {
        fun httpClientProvider(): HttpClientProvider
    }

    private lateinit var httpClientProvider: HttpClientProvider

    override fun onCreate() {
        super.onCreate()

        httpClientProvider = EntryPointAccessors
            .fromApplication(this, NetworkDependencies::class.java)
            .httpClientProvider()

        // Statically referenced and therefore not Hilt-injectable; hand it the
        // shared provider so its probe client reuses the app-wide pool.
        FileNameResolver.installSharedClientProvider(httpClientProvider)
    }

}
