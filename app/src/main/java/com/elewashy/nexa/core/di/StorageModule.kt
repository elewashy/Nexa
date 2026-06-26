package com.elewashy.nexa.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.storage.DataStoreAppPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import javax.inject.Singleton

/**
 * Provides the single [DataStore]`<Preferences>` used by [AppPreferences].
 *
 * We build the DataStore manually (rather than the `by preferencesDataStore`
 * delegate) so Hilt controls its lifetime and we can scope it to an
 * [IoDispatcher] + [SupervisorJob] rather than the default `GlobalScope`.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    private const val DATASTORE_FILE_NAME = "nexa_app_prefs"

    @Provides
    @Singleton
    fun provideAppPreferencesDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): DataStore<Preferences> {
        // Keep DataStore's internal coroutines off the default / main pools and
        // insulate it from crashes in user-supplied collectors.
        val dataStoreScope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(DATASTORE_FILE_NAME) }
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindsModule {

    @Binds
    @Singleton
    abstract fun bindAppPreferences(
        impl: DataStoreAppPreferences
    ): AppPreferences
}
