package com.elewashy.nexa.feature.browser.presentation

data class BrowserUiState(
    val toolbarVisible: Boolean = true,
    val backButtonEnabled: Boolean = false,
    val forwardButtonEnabled: Boolean = false,
    val goButtonVisible: Boolean = true,
    val refreshButtonVisible: Boolean = true,
    val moreOptionsVisible: Boolean = true,
    val linkButtonVisible: Boolean = true,
    val urlBarVisible: Boolean = false,
    val topSearchBarText: String = "",
    val progress: ProgressState = ProgressState.Hidden,
    val keepScreenOn: Boolean = false,
)

sealed class ProgressState {
    data object Hidden : ProgressState()
    data class Loading(val percent: Int) : ProgressState()
}
