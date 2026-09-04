package com.elewashy.nexa.feature.browser.presentation

import com.elewashy.nexa.feature.history.domain.model.HistorySuggestion

enum class BrowserOmniboxMode {
    Collapsed,
    Preview,
    Search,
    EditUrl;

    val isOverlayVisible: Boolean get() = this == Search || this == EditUrl
}

data class BrowserOmniboxState(
    val mode: BrowserOmniboxMode = BrowserOmniboxMode.Collapsed,
    val query: String = "",
    val localResults: List<HistorySuggestion> = emptyList(),
    val remoteResults: List<String> = emptyList(),
    val frequentSites: List<HistorySuggestion> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val matchingSearchHistory: List<String> = emptyList(),
)
