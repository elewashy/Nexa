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

    fun onOnboardingFinished() {
        _uiState.value = SplashUiState.Loading
        viewModelScope.launch {
            prefs.setOnboardingCompleted(true)
            initializeBlocklists()
            _uiState.value = SplashUiState.Ready
        }
    }

    private fun checkNetworkAndProceed() {
        if (!networkMonitor.isOnline()) {
            _uiState.value = SplashUiState.NoInternet
            return
        }

        // Non-blocking update check — runs in background, result observed on browser screen
        viewModelScope.launch {
            if (prefs.autoUpdateCheck.first()) {
                try { managerUpdateRepository.refresh() } catch (_: Exception) {}
            }
        }

        // Proceed immediately
        viewModelScope.launch {
            val onboarded = prefs.onboardingCompleted.first()
            if (onboarded) {
                initializeBlocklists()
                _uiState.value = SplashUiState.Ready
            } else {
                _uiState.value = SplashUiState.Onboarding
            }
        }
    }
}
