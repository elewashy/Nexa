package com.elewashy.nexa.feature.downloads.presentation.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.domain.usecase.CancelDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ObserveDownloadsUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.PauseDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.RemoveDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ResumeDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.RetryDownloadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Downloads screen.
 *
 * Owns the screen's entire UI state — dialog visibility, multi-select set,
 * and the live downloads list mirrored from the repository. The Activity is a
 * thin host that:
 *  - Renders `uiState` with `collectAsStateWithLifecycle`.
 *  - Forwards click callbacks to methods on this VM.
 *  - Handles only Android-native concerns (permissions, FileProvider, APK
 *    install, toasts) that cannot move into the VM.
 *
 * All download-engine commands go through use cases backed by the
 * `DownloadRepository` (the SSOT introduced in sub-phase 3.3).
 *
 * NOTE: File-system deletion (`deleteFileFromDevice` / batch delete) stays in
 * the Activity because it requires runtime-permission prompts and a
 * `FileProvider`. The VM exposes `cancel`/`remove` so the Activity can
 * sequence the delete-then-cancel flow.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    observeDownloads: ObserveDownloadsUseCase,
    private val pauseDownload: PauseDownloadUseCase,
    private val resumeDownload: ResumeDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val removeDownload: RemoveDownloadUseCase,
    private val retryDownload: RetryDownloadUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        // Mirror the repository's sorted snapshot into the UI state. Selection
        // and dialog flags are preserved across emissions.
        viewModelScope.launch {
            observeDownloads().collect { snapshot ->
                _uiState.update { state ->
                    val validIds = snapshot.mapTo(HashSet()) { it.id }
                    val prunedSelection = state.selectedItems.filterTo(HashSet()) { it in validIds }
                    state.copy(
                        downloads = snapshot,
                        selectedItems = prunedSelection,
                        // Auto-exit multi-select if the selection was emptied by a remote removal
                        isMultiSelectMode = state.isMultiSelectMode && prunedSelection.isNotEmpty(),
                        // Drop any dialog pointing at an item that no longer exists
                        cancelDialogItem = state.cancelDialogItem?.takeIf { it.id in validIds },
                        moreOptionsDialogItem = state.moreOptionsDialogItem?.takeIf { it.id in validIds },
                        deleteSelectedItems = state.deleteSelectedItems.filter { it.id in validIds }
                    )
                }
            }
        }
    }

    // ── Item click / long-click ──────────────────────────────────────

    /** Returns true if the Activity should handle the click (e.g. open the file). */
    fun onItemClick(item: DownloadItem): ItemClickAction {
        val state = _uiState.value
        return when {
            state.isMultiSelectMode -> {
                toggleSelection(item.id)
                ItemClickAction.Handled
            }
            item.status == DownloadStatus.COMPLETED -> ItemClickAction.OpenFile(item)
            else -> ItemClickAction.Handled
        }
    }

    fun onItemLongClick(item: DownloadItem) {
        val state = _uiState.value
        if (!state.isMultiSelectMode) {
            _uiState.update { it.copy(isMultiSelectMode = true) }
            toggleSelection(item.id)
        }
    }

    // ── Multi-select helpers ─────────────────────────────────────────

    fun toggleSelection(itemId: Long) {
        _uiState.update { state ->
            val newSelection = if (itemId in state.selectedItems) state.selectedItems - itemId
                               else state.selectedItems + itemId
            state.copy(
                selectedItems = newSelection,
                isMultiSelectMode = newSelection.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedItems = emptySet(), isMultiSelectMode = false) }
    }

    fun onSelectAll() {
        _uiState.update { state ->
            val allIds = state.downloads.mapTo(HashSet()) { it.id }
            val newSelection = if (state.selectedItems.size == allIds.size) emptySet() else allIds
            state.copy(
                selectedItems = newSelection,
                isMultiSelectMode = newSelection.isNotEmpty()
            )
        }
    }

    /** User tapped back in multi-select mode → just exit selection. Returns true if consumed. */
    fun onBackPressed(): Boolean {
        return if (_uiState.value.isMultiSelectMode) {
            clearSelection()
            true
        } else {
            false
        }
    }

    // ── Engine commands ──────────────────────────────────────────────

    fun pause(item: DownloadItem) { viewModelScope.launch { pauseDownload(item.id) } }
    fun resume(item: DownloadItem) { viewModelScope.launch { resumeDownload(item.id) } }
    fun retry(item: DownloadItem) { viewModelScope.launch { retryDownload(item.id) } }

    /** Used by the confirmation-dialog positive button. Cancels + deletes on disk. */
    fun confirmCancel() {
        val target = _uiState.value.cancelDialogItem ?: return
        viewModelScope.launch { cancelDownload(target.id) }
        dismissCancelDialog()
    }

    /** Used when the Activity has already deleted the file on disk itself. */
    fun cancelInEngine(id: Long) { viewModelScope.launch { cancelDownload(id) } }

    /** Remove from list but keep the on-disk file. */
    fun removeFromList(id: Long) { viewModelScope.launch { removeDownload(id) } }

    // ── Dialog state ─────────────────────────────────────────────────

    fun showCancelDialog(item: DownloadItem) {
        _uiState.update { it.copy(cancelDialogItem = item) }
    }

    fun dismissCancelDialog() {
        _uiState.update { it.copy(cancelDialogItem = null) }
    }

    fun showMoreOptionsDialog(item: DownloadItem) {
        _uiState.update { it.copy(moreOptionsDialogItem = item) }
    }

    fun dismissMoreOptionsDialog() {
        _uiState.update { it.copy(moreOptionsDialogItem = null) }
    }

    fun showDeleteSelectedDialog() {
        _uiState.update { state ->
            val items = state.downloads.filter { it.id in state.selectedItems }
            state.copy(deleteSelectedItems = items)
        }
    }

    fun dismissDeleteSelectedDialog() {
        _uiState.update { it.copy(deleteSelectedItems = emptyList()) }
    }

    /** Clears multi-select after a batch delete / remove. */
    fun onBatchOperationFinished() {
        clearSelection()
    }

    /**
     * Result of [onItemClick] — the Activity handles file-open itself because
     * it needs `FileProvider`, APK install permission prompts, and toasts.
     */
    sealed class ItemClickAction {
        data object Handled : ItemClickAction()
        data class OpenFile(val item: DownloadItem) : ItemClickAction()
    }
}
