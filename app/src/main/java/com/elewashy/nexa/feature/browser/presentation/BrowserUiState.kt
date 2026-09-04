package com.elewashy.nexa.feature.browser.presentation

data class BrowserUiState(
    val toolbarVisible: Boolean = true,
    val backButtonEnabled: Boolean = false,
    val forwardButtonEnabled: Boolean = false,
    val goButtonVisible: Boolean = true,
    val refreshButtonVisible: Boolean = true,
    val moreOptionsVisible: Boolean = true,
    val linkButtonVisible: Boolean = true,
    val topSearchBarText: String = "",
    val pageTitle: String = "",
    val isCurrentPageBookmarked: Boolean = false,
    val progress: ProgressState = ProgressState.Hidden,
    val keepScreenOn: Boolean = false,
    val pageLoadId: Int = 0,
    /** Main-frame load failed; lets the UI layer render an error page. */
    val pageLoadError: Boolean = false,
)

sealed class ProgressState {
    data object Hidden : ProgressState()
    data class Loading(val percent: Int) : ProgressState()
}

/** Accepts progress only for an active page load and keeps determinate progress monotonic. */
internal fun ProgressState.withWebProgress(percent: Int, minimumStep: Int = 5): ProgressState {
    val loading = this as? ProgressState.Loading ?: return this
    val bounded = percent.coerceIn(0, 100)
    return when {
        bounded >= 100 -> ProgressState.Hidden
        bounded <= loading.percent -> loading
        bounded - loading.percent < minimumStep -> loading
        else -> ProgressState.Loading(bounded)
    }
}
