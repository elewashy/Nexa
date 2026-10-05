package com.elewashy.nexa.feature.onboarding

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.common.DispatcherProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.core.storage.DataStoreAppPreferences
import com.elewashy.nexa.feature.onboarding.domain.usecase.CompleteOnboardingUseCase
import com.elewashy.nexa.feature.tabs.data.TabRepositoryImpl
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Real persistence for onboarding tests: a DataStore file and an in-memory Room workspace, the
 * same implementations the app injects.
 */
class OnboardingTestEnvironment(
    directory: File,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : Closeable {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val database = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val preferences = DataStoreAppPreferences(
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "onboarding-${System.nanoTime()}.preferences_pb") },
        ),
        context = context,
        appScope = scope,
    )

    val tabs = TabRepositoryImpl(database.tabsDao(), scope, SingleDispatcher(dispatcher))

    val completeOnboarding = CompleteOnboardingUseCase(preferences, tabs)

    override fun close() = database.close()

    private class SingleDispatcher(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
        override val mainImmediate = dispatcher
    }
}
