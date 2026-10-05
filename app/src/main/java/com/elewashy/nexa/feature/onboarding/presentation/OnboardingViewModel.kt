package com.elewashy.nexa.feature.onboarding.presentation

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.onboarding.domain.usecase.CompleteOnboardingUseCase
import com.elewashy.nexa.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The choices offered during onboarding, as currently persisted. */
@Immutable
data class OnboardingUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val searchEngine: SearchEngine = SearchEngine.DEFAULT,
    val navigationBarPosition: BrowserNavigationBarPosition = BrowserNavigationBarPosition.Bottom,
)

/**
 * Onboarding choices are regular settings: each one is written to DataStore the moment it is
 * picked, so they survive process death mid-flow and stay editable in Settings afterwards. The
 * theme and navigation-bar position are edited on their real pages (their own ViewModels);
 * this state only shows the current values. Which step is open is UI state, saved by the screen.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val completeOnboarding: CompleteOnboardingUseCase,
) : ViewModel() {

    val uiState: StateFlow<OnboardingUiState> = appPreferences.settings
        .map { settings ->
            OnboardingUiState(
                theme = AppTheme.fromPreferenceValue(settings.themeMode),
                searchEngine = SearchEngine.fromStoredValue(settings.selectedSearchEngine),
                navigationBarPosition = BrowserNavigationBarPosition.fromStoredValue(
                    settings.browserNavigationBarPosition,
                ),
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OnboardingUiState())

    private var completionRequested = false

    fun selectSearchEngine(engine: SearchEngine) {
        viewModelScope.launch { appPreferences.setSelectedSearchEngine(engine.storedValue) }
    }

    /**
     * Finishes onboarding (also used by Skip). Repeated taps write once; a failed write (storage
     * error) is logged and the next tap tries again instead of crashing.
     */
    fun complete() {
        if (completionRequested) return
        completionRequested = true
        viewModelScope.launch {
            try {
                completeOnboarding()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not save onboarding completion", e)
                completionRequested = false
            }
        }
    }

    private companion object {
        const val TAG = "Onboarding"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
