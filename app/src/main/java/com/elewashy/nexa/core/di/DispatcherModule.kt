package com.elewashy.nexa.core.di

import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.DefaultDispatcher
import com.elewashy.nexa.core.common.DefaultDispatcherProvider
import com.elewashy.nexa.core.common.DispatcherProvider
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.common.MainDispatcher
import com.elewashy.nexa.core.common.MainImmediateDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides coroutine dispatchers and a singleton application-scoped
 * [CoroutineScope] that is used for fire-and-forget work which must outlive
 * Activity/ViewModel lifetimes.
 *
 * Replaces the previous misuse of `GlobalScope` (e.g. in `SplashActivity`
 * blocklist initialisation).
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @MainImmediateDispatcher
    fun provideMainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * App-scoped supervisor scope. Children launched here are not cancelled when
     * a caller Activity / ViewModel dies. A [SupervisorJob] ensures
     * one failing child does not cancel its siblings.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DispatcherBindsModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(
        impl: DefaultDispatcherProvider
    ): DispatcherProvider
}
