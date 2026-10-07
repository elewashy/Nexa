package com.elewashy.nexa.feature.browser.domain.usecase

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.storage.DataStoreAppPreferences
import com.elewashy.nexa.feature.browser.domain.model.HomePage
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
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
class ResolveHomePageUseCaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `defaults to the search engine home page and follows engine changes`() = runTest {
        val preferences = createPreferences(this)
        val useCase = ResolveHomePageUseCase(preferences)

        assertEquals(HomePage.SearchEngineHome, useCase.homePage.first())
        assertEquals(SearchEngine.Google.homeUrl, useCase())

        preferences.setSelectedSearchEngine(SearchEngine.DuckDuckGo.storedValue)
        assertEquals(SearchEngine.DuckDuckGo.homeUrl, useCase())
    }

    @Test
    fun `custom home page is stored normalized and wins over the engine`() = runTest {
        val preferences = createPreferences(this)
        val useCase = ResolveHomePageUseCase(preferences)

        preferences.setHomePageUrl("Example.com/start")
        preferences.setSelectedSearchEngine(SearchEngine.Bing.storedValue)

        assertEquals("https://example.com/start", preferences.homePageUrl.first())
        assertEquals(HomePage.Custom("https://example.com/start"), useCase.homePage.first())
        assertEquals("https://example.com/start", useCase())
    }

    @Test
    fun `clearing or storing an invalid address restores the engine home page`() = runTest {
        val preferences = createPreferences(this)
        val useCase = ResolveHomePageUseCase(preferences)

        preferences.setHomePageUrl("https://example.com/")
        preferences.setHomePageUrl(null)
        assertNull(preferences.homePageUrl.first())
        assertEquals(SearchEngine.Google.homeUrl, useCase())

        preferences.setHomePageUrl("javascript:alert(1)")
        assertNull(preferences.homePageUrl.first())
        assertEquals(SearchEngine.Google.homeUrl, useCase())
    }

    private fun createPreferences(scope: TestScope): DataStoreAppPreferences {
        val file = File(temporaryFolder.root, "home-page-${System.nanoTime()}.preferences_pb")
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
