package com.elewashy.nexa.ui.components.navigation

data class BrowserNavBarState(
    val toolbarVisible: Boolean,
    val backEnabled: Boolean,
    val forwardEnabled: Boolean,
    val refreshVisible: Boolean,
    val homeVisible: Boolean,
    val moreOptionsVisible: Boolean,
    val linkButtonVisible: Boolean,
    val addressPreviewVisible: Boolean,
    val urlText: String,
    val pageTitle: String,
    val progressPercent: Int?,
    val currentUrl: String?,
    val tabCount: Int,
    val isPrivate: Boolean,
    val canBookmarkCurrentPage: Boolean,
    val isCurrentPageBookmarked: Boolean,
)
