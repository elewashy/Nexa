package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.downloads.domain.model.DownloadSettingsDefaults
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataStoreDownloadSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun firstRunUsesProductionDefaults() = runTest {
        val preferences = createPreferences(this)

        assertEquals(3, preferences.maxConcurrentDownloads.first())
        assertEquals(DownloadSettingsDefaults.DEFAULT_FILTER_IDS, preferences.downloadFilterIds.first())
        assertEquals(0L, preferences.downloadSpeedLimitBytesPerSecond.first())
        assertEquals(true, preferences.autoRetryDownloads.first())
        assertEquals(true, preferences.visualVideoPresentation.first())
        assertEquals(true, preferences.showDownloadFilterCounts.first())
        assertEquals(
            BrowserNavigationBarPosition.Bottom.storedValue,
            preferences.browserNavigationBarPosition.first(),
        )
    }

    @Test
    fun writesSanitizeUnsafeValuesAndRemainReactive() = runTest {
        val preferences = createPreferences(this)

        preferences.setMaxConcurrentDownloads(999)
        preferences.setDownloadSpeedLimitBytesPerSecond(777L * 1024)
        preferences.setDownloadFilterIds(setOf("pdf", "not-a-category"))
        preferences.setVisualVideoPresentation(false)
        preferences.setShowDownloadFilterCounts(false)
        preferences.setBrowserNavigationBarPosition(BrowserNavigationBarPosition.Top.storedValue)

        assertEquals(5, preferences.maxConcurrentDownloads.first())
        assertEquals(777L * 1024, preferences.downloadSpeedLimitBytesPerSecond.first())
        assertEquals(setOf("pdf"), preferences.downloadFilterIds.first())
        assertEquals(false, preferences.visualVideoPresentation.first())
        assertEquals(false, preferences.showDownloadFilterCounts.first())
        assertEquals(
            BrowserNavigationBarPosition.Top.storedValue,
            preferences.browserNavigationBarPosition.first(),
        )

        preferences.setBrowserNavigationBarPosition(Int.MAX_VALUE)
        assertEquals(
            BrowserNavigationBarPosition.Bottom.storedValue,
            preferences.browserNavigationBarPosition.first(),
        )
    }

    private fun createPreferences(scope: TestScope): DataStoreAppPreferences {
        val file = File(temporaryFolder.root, "download-settings-${System.nanoTime()}.preferences_pb")
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
