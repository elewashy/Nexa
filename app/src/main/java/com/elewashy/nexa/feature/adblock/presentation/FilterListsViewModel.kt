package com.elewashy.nexa.feature.adblock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.adblock.data.AdBlockRepository
import com.elewashy.nexa.feature.adblock.data.FilterListRepository
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatus
import com.elewashy.nexa.feature.adblock.domain.model.FilterListCategory
import com.elewashy.nexa.feature.adblock.domain.model.FilterListInfo
import com.elewashy.nexa.feature.adblock.domain.usecase.FilterUpdateOutcome
import com.elewashy.nexa.feature.adblock.domain.usecase.UpdateFilterListsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilterListGroup(val category: FilterListCategory, val lists: List<FilterListInfo>)

data class FilterListsUiState(
    val groups: List<FilterListGroup>,
    val status: AdBlockStatus,
) {
    val enabledCount: Int get() = groups.sumOf { group -> group.lists.count { it.enabled } }
}

/** Messages shown once as a snackbar. */
sealed interface FilterListsMessage {
    data class Update(val outcome: FilterUpdateOutcome) : FilterListsMessage
    data object Imported : FilterListsMessage
    data object Removed : FilterListsMessage
}

@HiltViewModel
class FilterListsViewModel @Inject constructor(
    private val filterListRepository: FilterListRepository,
    private val updateFilterLists: UpdateFilterListsUseCase,
    adBlockRepository: AdBlockRepository,
) : ViewModel() {

    val uiState: StateFlow<FilterListsUiState?> = combine(
        adBlockRepository.filterLists,
        adBlockRepository.status,
    ) { lists, status ->
        val groups = lists.groupBy { it.category }
            .toSortedMap(compareBy { it.ordinal })
            .map { (category, items) -> FilterListGroup(category, items) }
        FilterListsUiState(groups, status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _message = MutableStateFlow<FilterListsMessage?>(null)
    val message: StateFlow<FilterListsMessage?> = _message.asStateFlow()

    /** Result of the import dialog: null while idle. Survives configuration changes. */
    private val _importResult = MutableStateFlow<FilterListRepository.ImportResult?>(null)
    val importResult: StateFlow<FilterListRepository.ImportResult?> = _importResult.asStateFlow()

    fun setEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch { filterListRepository.setEnabled(key, enabled) }
    }

    fun updateNow() {
        if (_updating.value) return
        _updating.value = true
        viewModelScope.launch {
            try {
                _message.value = FilterListsMessage.Update(updateFilterLists())
            } finally {
                _updating.value = false
            }
        }
    }

    /** Validates and imports [url]; downloading starts automatically once the list is selected. */
    fun import(url: String) {
        viewModelScope.launch {
            val result = filterListRepository.import(url)
            _importResult.value = result
            if (result is FilterListRepository.ImportResult.Added) _message.value = FilterListsMessage.Imported
        }
    }

    fun onImportResultHandled() {
        _importResult.value = null
    }

    fun remove(key: String) {
        viewModelScope.launch {
            filterListRepository.removeImported(key)
            _message.value = FilterListsMessage.Removed
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
