package com.elewashy.nexa.feature.onboarding.presentation

import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.onboarding.OnboardingTestEnvironment
import com.elewashy.nexa.feature.onboarding.domain.usecase.ShouldShowOnboardingUseCase
import com.elewashy.nexa.ui.startup.StartupDestination
import com.elewashy.nexa.ui.startup.StartupViewModel
import com.elewashy.nexa.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The startup gate and the onboarding choices, over a real DataStore file. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelsTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private var environment: OnboardingTestEnvironment? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        environment?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `first launch goes from loading to onboarding, and to the browser once completed`() = runTest {
        val env = environment(backgroundScope)
        val preferences = env.preferences
        val startup = StartupViewModel(ShouldShowOnboardingUseCase(preferences))
        val onboarding = OnboardingViewModel(preferences, env.completeOnboarding)

        assertEquals(StartupDestination.Loading, startup.destination.value)
        assertEquals(StartupDestination.Onboarding, startup.destination.awaitResolved())

        onboarding.complete()
        startup.destination.first { it == StartupDestination.Browser }
        assertTrue(preferences.onboardingCompleted.first())
    }

    @Test
    fun `a returning user opens straight into the browser`() = runTest {
        val env = environment(backgroundScope)
        val preferences = env.preferences
        env.completeOnboarding()

        // A new Activity (restart, configuration change or process death) gets a new ViewModel.
        val startup = StartupViewModel(ShouldShowOnboardingUseCase(preferences))

        assertEquals(StartupDestination.Browser, startup.destination.awaitResolved())
    }

    @Test
    fun `the search engine is persisted when picked, and choices made on the real pages are shown`() = runTest {
        val env = environment(backgroundScope)
        val preferences = env.preferences
        val viewModel = OnboardingViewModel(preferences, env.completeOnboarding)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.selectSearchEngine(SearchEngine.DuckDuckGo)
        // Written by the real Theme and navigation-bar pages; onboarding shows the result.
        preferences.setThemeMode(AppTheme.DARK.preferenceValue)
        preferences.setBrowserNavigationBarPosition(BrowserNavigationBarPosition.Top.storedValue)

        val expected = OnboardingUiState(
            theme = AppTheme.DARK,
            searchEngine = SearchEngine.DuckDuckGo,
            navigationBarPosition = BrowserNavigationBarPosition.Top,
        )
        assertEquals(expected, viewModel.uiState.first { it == expected })
        // A regular setting: Settings and the browser read the same value.
        assertEquals(SearchEngine.DuckDuckGo.storedValue, preferences.selectedSearchEngine.first())
    }

    @Test
    fun `defaults are offered before anything is picked`() = runTest {
        val env = environment(backgroundScope)
        val preferences = env.preferences
        val viewModel = OnboardingViewModel(preferences, env.completeOnboarding)
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Wait for the real DataStore read, then check what it produced.
        preferences.settings.first()
        advanceUntilIdle()
        assertEquals(OnboardingUiState(), viewModel.uiState.value)
    }

    private suspend fun StateFlow<StartupDestination>.awaitResolved(): StartupDestination =
        first { it != StartupDestination.Loading }

    private fun environment(scope: CoroutineScope) =
        OnboardingTestEnvironment(temporaryFolder.root, scope).also { environment = it }
}
