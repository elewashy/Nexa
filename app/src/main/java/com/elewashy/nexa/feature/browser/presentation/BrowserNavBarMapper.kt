package com.elewashy.nexa.feature.browser.presentation

import com.elewashy.nexa.core.util.SafeUrls.isSafeLoadableUrl
import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import com.elewashy.nexa.ui.components.navigation.BrowserNavBarState

internal fun BrowserUiState.toNavBarState(
    addressPreviewVisible: Boolean,
    workspace: TabWorkspaceState = TabWorkspaceState(),
): BrowserNavBarState {
    val activeMode = workspace.activeTab?.browsingMode ?: BrowsingMode.Normal
    return BrowserNavBarState(
        toolbarVisible = toolbarVisible,
        backEnabled = backButtonEnabled,
        forwardEnabled = forwardButtonEnabled,
        refreshVisible = refreshButtonVisible,
        homeVisible = goButtonVisible,
        moreOptionsVisible = moreOptionsVisible,
        linkButtonVisible = linkButtonVisible,
        addressPreviewVisible = addressPreviewVisible,
        urlText = topSearchBarText,
        pageTitle = pageTitle,
        progressPercent = (progress as? ProgressState.Loading)
            ?.percent
            ?.takeIf { it in 1..99 },
        currentUrl = topSearchBarText.ifBlank { null },
        tabCount = workspace.tabs.count { it.browsingMode == activeMode }.coerceAtLeast(1),
        isPrivate = activeMode == BrowsingMode.Private,
        canBookmarkCurrentPage = isSafeLoadableUrl(topSearchBarText),
        isCurrentPageBookmarked = isCurrentPageBookmarked,
    )
}
