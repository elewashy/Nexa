package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataStoreSearchEngineSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `first run defaults to Google`() = runTest {
        val preferences = createPreferences(this)
        assertEquals(SearchEngine.Google.storedValue, preferences.selectedSearchEngine.first())
    }

    @Test
    fun `persists and reads back a non-default engine`() = runTest {
        val preferences = createPreferences(this)
        preferences.setSelectedSearchEngine(SearchEngine.DuckDuckGo.storedValue)
        assertEquals(SearchEngine.DuckDuckGo.storedValue, preferences.selectedSearchEngine.first())
    }

    @Test
    fun `sanitizes unknown stored values to Google`() = runTest {
        val preferences = createPreferences(this)
        preferences.setSelectedSearchEngine(Int.MAX_VALUE)
        assertEquals(SearchEngine.Google.storedValue, preferences.selectedSearchEngine.first())
    }

    @Test
    fun `sanitizes negative stored values to Google`() = runTest {
        val preferences = createPreferences(this)
        preferences.setSelectedSearchEngine(-1)
        assertEquals(SearchEngine.Google.storedValue, preferences.selectedSearchEngine.first())
    }

    @Test
    fun `round-trips every supported engine`() = runTest {
        val preferences = createPreferences(this)
        SearchEngine.entries.forEach { engine ->
            preferences.setSelectedSearchEngine(engine.storedValue)
            assertEquals(engine.storedValue, preferences.selectedSearchEngine.first())
        }
    }

    private fun createPreferences(scope: TestScope): DataStoreAppPreferences {
        val file = File(temporaryFolder.root, "search-engine-${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file },
        )
        return DataStoreAppPreferences(
            dataStore = store,
            context = ApplicationProvider.getApplicationContext<Context>(),
            appScope = scope.backgroundScope,
        )
    }
}
