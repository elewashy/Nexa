package com.elewashy.nexa.feature.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.elewashy.nexa.feature.history.data.HistoryRepository
import com.elewashy.nexa.feature.history.domain.model.HistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val history: Flow<PagingData<HistoryItem>> = _searchQuery
        .map(String::trim)
        .distinctUntilChanged()
        .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .flatMapLatest(repository::observeHistory)
        .cachedIn(viewModelScope)

    private val mutationMutex = Mutex()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _selectionBusy = MutableStateFlow(false)
    val selectionBusy: StateFlow<Boolean> = _selectionBusy.asStateFlow()
    private var selectionRequestVersion = 0

    /** Rows removed by the latest delete operation, restorable while its message is visible. */
    private val _undoItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val undoItems: StateFlow<List<HistoryItem>> = _undoItems.asStateFlow()

    private val _showClearDialog = MutableStateFlow(false)
    val showClearDialog: StateFlow<Boolean> = _showClearDialog.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query.take(MAX_SEARCH_QUERY_LENGTH)
    }

    fun toggleSelection(id: Long) {
        _selectedIds.value = if (id in _selectedIds.value) {
            _selectedIds.value - id
        } else {
            _selectedIds.value + id
        }
    }

    fun select(id: Long) {
        _selectedIds.value = _selectedIds.value + id
    }

    fun selectAllMatching() {
        val requestVersion = ++selectionRequestVersion
        val query = _searchQuery.value
        _selectionBusy.value = true
        viewModelScope.launch {
            val ids = runCatching { repository.matchingIds(query) }.getOrNull()
            if (requestVersion == selectionRequestVersion) {
                ids?.let { _selectedIds.value = it }
                _selectionBusy.value = false
            }
        }
    }

    fun clearSelection() {
        selectionRequestVersion++
        _selectionBusy.value = false
        _selectedIds.value = emptySet()
    }

    fun deleteSelection() {
        if (_selectionBusy.value) return
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            mutationMutex.withLock {
                _undoItems.value = repository.delete(ids)
            }
        }
    }

    fun delete(item: HistoryItem) {
        viewModelScope.launch {
            mutationMutex.withLock {
                repository.delete(item.id)
                // Offer undo only after the delete is committed, preventing an
                // immediate undo from racing the database write.
                _undoItems.value = listOf(item)
            }
        }
    }

    fun undo() {
        val items = _undoItems.value
        if (items.isEmpty()) return
        _undoItems.value = emptyList()
        viewModelScope.launch {
            mutationMutex.withLock { repository.reinsert(items) }
        }
    }

    fun dismissUndo() {
        _undoItems.value = emptyList()
    }

    fun requestClear() {
        _showClearDialog.value = true
    }

    fun dismissClear() {
        _showClearDialog.value = false
    }

    fun confirmClear() {
        _showClearDialog.value = false
        clearSelection()
        viewModelScope.launch {
            mutationMutex.withLock {
                _undoItems.value = repository.clearAll()
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MAX_SEARCH_QUERY_LENGTH = 256
    }
}
