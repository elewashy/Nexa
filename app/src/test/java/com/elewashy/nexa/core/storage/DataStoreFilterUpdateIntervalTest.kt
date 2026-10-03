package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.feature.browser.domain.model.FilterUpdateInterval
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataStoreFilterUpdateIntervalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `defaults to daily`() = runTest {
        assertEquals(FilterUpdateInterval.Daily.hours, createPreferences(this).filterUpdateIntervalHours.first())
    }

    @Test
    fun `round-trips every interval including manual`() = runTest {
        val preferences = createPreferences(this)
        FilterUpdateInterval.entries.forEach { interval ->
            preferences.setFilterUpdateIntervalHours(interval.hours)
            assertEquals(interval.hours, preferences.filterUpdateIntervalHours.first())
            assertEquals(interval.hours, preferences.settings.first().filterUpdateIntervalHours)
        }
    }

    @Test
    fun `sanitizes unknown values to the default`() = runTest {
        val preferences = createPreferences(this)
        preferences.setFilterUpdateIntervalHours(5)
        assertEquals(FilterUpdateInterval.DEFAULT.hours, preferences.filterUpdateIntervalHours.first())
    }

    @Test
    fun `manual mode has no automatic interval`() {
        assertNull(FilterUpdateInterval.Manual.intervalMs)
        assertEquals(6 * 60 * 60 * 1000L, FilterUpdateInterval.SixHours.intervalMs)
    }

    private fun createPreferences(scope: TestScope): DataStoreAppPreferences {
        val file = File(temporaryFolder.root, "filter-interval-${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope.backgroundScope, produceFile = { file })
        return DataStoreAppPreferences(
            dataStore = store,
            context = ApplicationProvider.getApplicationContext<Context>(),
            appScope = scope.backgroundScope,
        )
    }
}
