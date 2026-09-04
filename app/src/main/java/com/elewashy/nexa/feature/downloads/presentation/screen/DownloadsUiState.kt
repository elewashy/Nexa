package com.elewashy.nexa.feature.downloads.presentation.screen

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem

/**
 * Single immutable snapshot describing what the Downloads screen should render.
 *
 * The ViewModel holds this in a `StateFlow` and mutates it via `update { it.copy(...) }`, so
 * dialog visibility and selection survive configuration changes without any Activity state.
 *
 * @property downloads            Current sorted snapshot (from the repository).
 * @property selectedItems        IDs of items selected while in multi-select mode.
 * @property isMultiSelectMode    Whether long-press selection mode is active.
 * @property cancelDialogItem     Non-null while the single-item cancel dialog is shown.
 * @property renameDialogItem     Non-null while the rename dialog is shown.
 * @property deleteConfirmation   Non-null while the delete confirmation dialog is shown.
 * @property notificationsWarning One-time warning when a download starts while
 *                                notifications are disabled; null once dismissed.
 */
data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val selectedItems: Set<Long> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val cancelDialogItem: DownloadItem? = null,
    val renameDialogItem: DownloadItem? = null,
    val deleteConfirmation: DeleteConfirmation? = null,
    val notificationsWarning: String? = null,
)

/**
 * Items awaiting the user's explicit confirmation. Deleting from the Download Manager also
 * removes the file from the device, so nothing is committed until [DownloadsViewModel.confirmDelete].
 */
data class DeleteConfirmation(val items: List<DownloadItem>) {
    val count: Int get() = items.size

    /** The single file name when exactly one item is affected; null for bulk deletion. */
    val singleFileName: String? get() = items.singleOrNull()?.fileName
}
