package com.elewashy.nexa.feature.splash.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.network.NetworkMonitor
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.splash.domain.usecase.InitializeBlocklistsUseCase
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val prefs: AppPreferences,
    private val initializeBlocklists: InitializeBlocklistsUseCase,
    private val managerUpdateRepository: ManagerUpdateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SplashUiState>(SplashUiState.Loading)
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        checkNetworkAndProceed()
    }

    fun onRetryClicked() {
        _uiState.value = SplashUiState.Loading
        checkNetworkAndProceed()
    }

    /**
     * Continues despite zero connectivity: cached resources carry the app,
     * and background refreshes catch up once a network returns. Reuses the
     * same continuation path as the online flow with `online = false` so the
     * update check is skipped.
     */
    fun onProceedAnywayClicked() {
        _uiState.value = SplashUiState.Loading
        proceedAfterNetworkCheck(online = false)
    }

    fun onOnboardingFinished() {
        _uiState.value = SplashUiState.Loading
        viewModelScope.launch {
            prefs.setOnboardingCompleted(true)
            initializeBlocklists()
            _uiState.value = SplashUiState.Ready
        }
    }

    private fun checkNetworkAndProceed() {
        val online = networkMonitor.isOnline()

        // Only hard-block when there is no network at all. Unvalidated
        // networks (captive portals) and transient outages proceed on cached
        // resources; background refreshes catch up once connectivity returns.
        if (!online && !networkMonitor.hasAnyNetwork()) {
            _uiState.value = SplashUiState.NoInternet
            return
        }

        proceedAfterNetworkCheck(online)
    }

    private fun proceedAfterNetworkCheck(online: Boolean) {
        viewModelScope.launch {
            val onboarded = prefs.onboardingCompleted.first()
            if (!onboarded) {
                // No background update check before onboarding consent.
                _uiState.value = SplashUiState.Onboarding
                return@launch
            }

            // Non-blocking update check — runs in background, result observed on
            // the browser screen. Only when actually online.
            if (online && prefs.autoUpdateCheck.first()) {
                viewModelScope.launch {
                    try { managerUpdateRepository.refresh() } catch (_: Exception) {}
                }
            }

            initializeBlocklists()
            _uiState.value = SplashUiState.Ready
        }
    }
}
