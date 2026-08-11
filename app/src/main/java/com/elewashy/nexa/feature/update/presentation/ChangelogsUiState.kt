package com.elewashy.nexa.feature.update.presentation

import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry

/**
 * UI state for the release/changelog list. The changelog endpoint returns
 * everything in one request (no real pagination), so the list is modeled as
 * a single load instead of paging data.
 */
sealed interface ChangelogsUiState {
    data object Loading : ChangelogsUiState

    data class Error(val message: String?) : ChangelogsUiState

    data class Loaded(val releases: List<ReleaseHistoryEntry>) : ChangelogsUiState
}
