package com.elewashy.nexa.feature.onboarding.domain.usecase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.elewashy.nexa.core.common.BrowserUrls
import com.elewashy.nexa.core.storage.DataStoreAppPreferences
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.onboarding.OnboardingTestEnvironment
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingUseCasesTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var environment: OnboardingTestEnvironment? = null

    @After
    fun tearDown() {
        environment?.close()
    }

    private fun TestScope.environment() =
        OnboardingTestEnvironment(temporaryFolder.root, backgroundScope).also { environment = it }

    @Test
    fun `onboarding is shown until it is completed, then never again`() = runTest {
        val env = environment()
        val shouldShow = ShouldShowOnboardingUseCase(env.preferences)

        shouldShow().test {
            assertTrue(awaitItem())
            env.completeOnboarding()
            assertFalse(awaitItem())
            // Completing twice does not emit again.
            env.completeOnboarding()
            expectNoEvents()
        }
        assertFalse(shouldShow().first())
    }

    @Test
    fun `the untouched first tab opens the search engine picked during onboarding`() = runTest {
        val env = environment()
        // As on a first launch: the browser state restores (and seeds) the workspace at startup.
        env.tabs.restore()
        assertEquals(BrowserUrls.HOME, env.tabs.workspace.value.tabs.single().url)

        env.preferences.setSelectedSearchEngine(SearchEngine.DuckDuckGo.storedValue)
        env.completeOnboarding()

        assertEquals(SearchEngine.DuckDuckGo.homeUrl, env.tabs.workspace.value.tabs.single().url)
        assertTrue(env.preferences.onboardingCompleted.first())
    }

    @Test
    fun `keeping the default engine leaves the first tab as it is`() = runTest {
        val env = environment()
        env.tabs.restore()

        env.completeOnboarding()

        assertEquals(SearchEngine.DEFAULT.homeUrl, env.tabs.workspace.value.tabs.single().url)
    }

    @Test
    fun `existing tabs are never changed`() = runTest {
        val env = environment()
        env.tabs.restore()
        val existing = env.tabs.workspace.value.tabs.single()
        env.tabs.newTab("https://example.com/")
        env.preferences.setSelectedSearchEngine(SearchEngine.Bing.storedValue)

        env.completeOnboarding()

        val urls = env.tabs.workspace.value.tabs.map { it.url }
        assertEquals(listOf(existing.url, "https://example.com/"), urls)
    }

    @Test
    fun `a first tab that already has a page is not changed`() = runTest {
        val env = environment()
        env.tabs.restore()
        val tab = env.tabs.workspace.value.tabs.single()
        env.tabs.titleReceived(tab.id, "Google")
        env.preferences.setSelectedSearchEngine(SearchEngine.Bing.storedValue)

        env.completeOnboarding()

        assertEquals(BrowserUrls.HOME, env.tabs.workspace.value.tabs.single().url)
    }

    @Test
    fun `an unreadable preferences file opens the browser instead of trapping the user`() = runTest {
        val unreadable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk error") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
                throw IOException("disk error")
        }
        // The preferences' own theme-mirroring collector fails on the same read; contain it the
        // way an exception handler would, so only the use case's behaviour is under test.
        val appScope = CoroutineScope(
            backgroundScope.coroutineContext +
                SupervisorJob(backgroundScope.coroutineContext.job) +
                CoroutineExceptionHandler { _, _ -> },
        )
        val shouldShow = ShouldShowOnboardingUseCase(
            DataStoreAppPreferences(unreadable, context, appScope),
        )

        assertEquals(false, shouldShow().first())
    }
}
