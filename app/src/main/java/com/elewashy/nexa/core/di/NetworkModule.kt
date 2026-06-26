package com.elewashy.nexa.core.di

import com.elewashy.nexa.core.network.ConnectivityNetworkMonitor
import com.elewashy.nexa.core.network.NetworkMonitor
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the shared [Gson] instance.
 *
 * `HttpClientProvider` and `ConnectivityNetworkMonitor` have `@Inject`
 * constructors so they do not need an explicit `@Provides` here — Hilt
 * discovers them automatically. We still bind [NetworkMonitor] to its
 * production implementation for consumers that depend on the interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(
        impl: ConnectivityNetworkMonitor
    ): NetworkMonitor
}
