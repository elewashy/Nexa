package com.elewashy.nexa.feature.downloads.presentation.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.domain.usecase.CancelDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.DismissNotificationsWarningUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ObserveDownloadsUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ObserveNotificationsWarningUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.PauseDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ResumeDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.RetryDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.RenameDownloadUseCase
import com.elewashy.nexa.feature.downloads.data.RenameDownloadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
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
 *  - Handles only Android-native concerns (FileProvider, APK install, toasts)
 *    that cannot move into the VM.
 *
 * All download-engine commands go through use cases backed by the
 * `DownloadRepository` (the SSOT introduced in sub-phase 3.3).
 *
 * Deleting also removes the file from the device, so every delete entry point
 * (card menu, multi-select header) first raises [DownloadsUiState.deleteConfirmation];
 * only [confirmDelete] commits through the repository.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    observeDownloads: ObserveDownloadsUseCase,
    observeNotificationsWarning: ObserveNotificationsWarningUseCase,
    private val dismissNotificationsWarningUseCase: DismissNotificationsWarningUseCase,
    private val pauseDownload: PauseDownloadUseCase,
    private val resumeDownload: ResumeDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val retryDownload: RetryDownloadUseCase,
    private val renameDownload: RenameDownloadUseCase,
    @param:ApplicationScope private val applicationScope: CoroutineScope
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
                        renameDialogItem = state.renameDialogItem?.takeIf { it.id in validIds },
                        deleteConfirmation = state.deleteConfirmation?.let { confirmation ->
                            val remaining = confirmation.items.filter { it.id in validIds }
                            if (remaining.isEmpty()) null else DeleteConfirmation(remaining)
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            observeNotificationsWarning().collect { warning ->
                _uiState.update { it.copy(notificationsWarning = warning) }
            }
        }
    }

    fun dismissNotificationsWarning() {
        dismissNotificationsWarningUseCase()
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

    // ── Engine commands ──────────────────────────────────────────────

    fun pause(item: DownloadItem) { viewModelScope.launch { pauseDownload(item.id) } }
    fun resume(item: DownloadItem) { viewModelScope.launch { resumeDownload(item.id) } }
    fun retry(item: DownloadItem) { viewModelScope.launch { retryDownload(item.id) } }

    /** Used by the cancel confirmation-dialog positive button. */
    fun confirmCancel() {
        val target = _uiState.value.cancelDialogItem ?: return
        viewModelScope.launch { cancelDownload(target.id) }
        dismissCancelDialog()
    }

    // ── Dialog state ─────────────────────────────────────────────────

    fun showCancelDialog(item: DownloadItem) {
        _uiState.update { it.copy(cancelDialogItem = item) }
    }

    fun dismissCancelDialog() {
        _uiState.update { it.copy(cancelDialogItem = null) }
    }

    fun showRenameDialog(item: DownloadItem) {
        if (item.status == DownloadStatus.COMPLETED) {
            _uiState.update { it.copy(renameDialogItem = item) }
        }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameDialogItem = null) }
    }

    fun confirmRename(name: String, onResult: (RenameDownloadResult) -> Unit) {
        val target = _uiState.value.renameDialogItem ?: return
        dismissRenameDialog()
        viewModelScope.launch { onResult(renameDownload(target.id, name)) }
    }

    // ── Delete (confirm, then commit) ─────────────────────────────────

    /** Card-level delete: asks for confirmation before the file leaves the device. */
    fun requestDelete(item: DownloadItem) {
        requestDelete(listOf(item))
    }

    /** Multi-select delete: one confirmation covering every selected item. */
    fun requestSelectedDelete() {
        val state = _uiState.value
        requestDelete(state.downloads.filter { it.id in state.selectedItems })
    }

    private fun requestDelete(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        _uiState.update { it.copy(deleteConfirmation = DeleteConfirmation(items)) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(deleteConfirmation = null) }
    }

    /**
     * Commits the confirmed deletion. Runs on the application scope so leaving the screen
     * mid-commit cannot leave some confirmed files deleted and others not.
     */
    fun confirmDelete() {
        val confirmation = _uiState.value.deleteConfirmation ?: return
        val deletedIds = confirmation.items.mapTo(HashSet()) { it.id }
        _uiState.update { state ->
            val remainingSelection = state.selectedItems - deletedIds
            state.copy(
                deleteConfirmation = null,
                selectedItems = remainingSelection,
                isMultiSelectMode = remainingSelection.isNotEmpty(),
            )
        }
        applicationScope.launch {
            confirmation.items.forEach { item -> cancelDownload(item.id) }
        }
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
