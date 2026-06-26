package com.elewashy.nexa.feature.browser.presentation

import com.elewashy.nexa.ui.components.navigation.BrowserNavBarState

internal fun BrowserUiState.toNavBarState(): BrowserNavBarState {
    val progressPercent = (progress as? ProgressState.Loading)?.percent
    return BrowserNavBarState(
        toolbarVisible = toolbarVisible,
        backEnabled = backButtonEnabled,
        forwardEnabled = forwardButtonEnabled,
        refreshVisible = refreshButtonVisible,
        homeVisible = goButtonVisible,
        moreOptionsVisible = moreOptionsVisible,
        linkButtonVisible = linkButtonVisible,
        urlBarVisible = urlBarVisible,
        urlText = topSearchBarText,
        progressPercent = progressPercent,
        currentUrl = topSearchBarText.ifBlank { null },
    )
}
