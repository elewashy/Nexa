package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreOnboardingStateTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh install has not completed onboarding`() = runTest {
        val file = newFile()
        val preferences = DataStoreAppPreferences(store(file, backgroundScope), context, backgroundScope)

        assertFalse(preferences.onboardingCompleted.first())
    }

    @Test
    fun `completion survives a process restart`() = runTest {
        val file = newFile()
        // First process: finish onboarding, then the process dies (its DataStore scope ends).
        val firstProcessScope = CoroutineScope(coroutineContext + SupervisorJob())
        DataStoreAppPreferences(store(file, firstProcessScope), context, firstProcessScope)
            .setOnboardingCompleted()
        firstProcessScope.coroutineContext.job.cancelAndJoin()

        // Second process: a new DataStore instance over the same file.
        val restarted = DataStoreAppPreferences(store(file, backgroundScope), context, backgroundScope)
        assertTrue(restarted.onboardingCompleted.first())
    }

    @Test
    fun `completion written by version 1_3_1 is honoured`() = runTest {
        val file = newFile()
        val dataStore = store(file, backgroundScope)
        // Exactly what releases up to 1.3.1 stored after onboarding.
        dataStore.edit { it[booleanPreferencesKey("onboarding_completed")] = true }

        val preferences = DataStoreAppPreferences(dataStore, context, backgroundScope)
        assertTrue(preferences.onboardingCompleted.first())
    }

    @Test
    fun `completing again is a no-op`() = runTest {
        val preferences = DataStoreAppPreferences(store(newFile(), backgroundScope), context, backgroundScope)

        preferences.setOnboardingCompleted()
        preferences.setOnboardingCompleted()

        assertTrue(preferences.onboardingCompleted.first())
    }

    private fun newFile() = File(temporaryFolder.root, "onboarding-${System.nanoTime()}.preferences_pb")

    private fun store(file: File, scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
}
