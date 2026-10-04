package com.elewashy.nexa.feature.adblock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.adblock.data.AdBlockStatsRepository
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatistics
import com.elewashy.nexa.feature.adblock.domain.model.BlockedDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdBlockStatisticsUiState(
    val statistics: AdBlockStatistics,
    val topDomains: List<BlockedDomain>,
)

@HiltViewModel
class AdBlockStatisticsViewModel @Inject constructor(
    private val repository: AdBlockStatsRepository,
) : ViewModel() {

    val uiState: StateFlow<AdBlockStatisticsUiState?> = combine(
        repository.statistics,
        repository.topDomains(TOP_DOMAINS),
        ::AdBlockStatisticsUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _resetDone = MutableStateFlow(false)
    val resetDone: StateFlow<Boolean> = _resetDone.asStateFlow()

    init {
        viewModelScope.launch { repository.flushPending() }
    }

    fun reset() {
        viewModelScope.launch {
            repository.reset()
            _resetDone.value = true
        }
    }

    fun onResetShown() {
        _resetDone.value = false
    }

    private companion object {
        const val TOP_DOMAINS = 20
    }
}
