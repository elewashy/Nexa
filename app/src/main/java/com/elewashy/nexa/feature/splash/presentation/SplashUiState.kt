package com.elewashy.nexa.feature.splash.presentation

sealed class SplashUiState {

    data object Loading : SplashUiState()

    data object NoInternet : SplashUiState()

    data object Onboarding : SplashUiState()

    data object Ready : SplashUiState()
}
