package com.elewashy.nexa.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.onboarding.domain.usecase.ShouldShowOnboardingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import javax.inject.Inject

/** What the launcher Activity shows first. */
enum class StartupDestination {
    /** The persisted onboarding state is still being read; the system splash covers this. */
    Loading,

    /** First launch: the onboarding flow. */
    Onboarding,

    /** Every other launch, and the first one once onboarding is done. */
    Browser,
}

/**
 * Decides the start destination from persisted state, which (unlike the saved back stack) is
 * the single source of truth across restarts, configuration changes and process death.
 *
 * The read starts eagerly with the ViewModel, so it runs in parallel with the rest of
 * `onCreate` and is normally done before the first frame; the splash screen reads [destination]
 * synchronously. Once the browser is chosen the decision is final for this Activity, and the
 * preferences stream is no longer collected.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    shouldShowOnboarding: ShouldShowOnboardingUseCase,
) : ViewModel() {

    val destination: StateFlow<StartupDestination> = shouldShowOnboarding()
        .map { show -> if (show) StartupDestination.Onboarding else StartupDestination.Browser }
        .transformWhile { destination ->
            emit(destination)
            destination != StartupDestination.Browser
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StartupDestination.Loading)
}
