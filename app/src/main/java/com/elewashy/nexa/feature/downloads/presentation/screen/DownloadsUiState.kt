package com.elewashy.nexa.feature.downloads.presentation.screen

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem

/**
 * Single immutable snapshot describing what the Downloads screen should render.
 *
 * Each `private var ... by mutableStateOf(...)` that lived directly on the
 * pre-refactor downloads activity moved here so the Activity no longer owns
 * any UI state. The ViewModel holds this in a `StateFlow` and mutates it via
 * `update { it.copy(...) }`.
 *
 * @property downloads           Current sorted snapshot (from the repository).
 * @property selectedItems       IDs of items selected while in multi-select mode.
 * @property isMultiSelectMode   Whether long-press selection mode is active.
 * @property cancelDialogItem    Non-null while the single-item cancel dialog is shown.
 * @property moreOptionsDialogItem  Non-null while the more-options dialog is shown.
 * @property deleteSelectedItems Non-empty while the batch delete dialog is shown.
 */
data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val selectedItems: Set<Long> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val cancelDialogItem: DownloadItem? = null,
    val moreOptionsDialogItem: DownloadItem? = null,
    val deleteSelectedItems: List<DownloadItem> = emptyList()
) {
    val showCancelDialog: Boolean get() = cancelDialogItem != null
    val showMoreOptionsDialog: Boolean get() = moreOptionsDialogItem != null
    val showDeleteSelectedDialog: Boolean get() = deleteSelectedItems.isNotEmpty()
}
