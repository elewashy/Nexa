package com.elewashy.nexa.feature.adblock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.AdBlockRepository
import com.elewashy.nexa.feature.adblock.data.AdBlockStatsRepository
import com.elewashy.nexa.feature.adblock.data.CustomRuleRepository
import com.elewashy.nexa.feature.adblock.data.SiteSettingsRepository
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatistics
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatus
import com.elewashy.nexa.feature.adblock.domain.model.FilterListState
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateInterval
import com.elewashy.nexa.feature.adblock.domain.usecase.FilterUpdateOutcome
import com.elewashy.nexa.feature.adblock.domain.usecase.FilterUpdateSchedule
import com.elewashy.nexa.feature.adblock.domain.usecase.ObserveFilterUpdateScheduleUseCase
import com.elewashy.nexa.feature.adblock.domain.usecase.UpdateFilterListsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Counts shown on the dashboard rows. */
data class FilterListsSummary(val enabled: Int, val total: Int, val failed: Int)

data class AdBlockDashboardUiState(
    val enabled: Boolean,
    val status: AdBlockStatus,
    val statistics: AdBlockStatistics,
    val lists: FilterListsSummary,
    val schedule: FilterUpdateSchedule,
    val customRuleCount: Int,
    val siteCount: Int,
)

@HiltViewModel
class AdBlockDashboardViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val updateFilterLists: UpdateFilterListsUseCase,
    adBlockRepository: AdBlockRepository,
    statsRepository: AdBlockStatsRepository,
    customRuleRepository: CustomRuleRepository,
    siteSettingsRepository: SiteSettingsRepository,
    observeSchedule: ObserveFilterUpdateScheduleUseCase,
) : ViewModel() {

    private val filterLists = adBlockRepository.filterLists
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    private val listsSummary = filterLists.map { lists ->
        FilterListsSummary(
            enabled = lists.count { it.enabled },
            total = lists.size,
            failed = lists.count { it.state == FilterListState.UpdateFailed || it.state == FilterListState.Unavailable },
        )
    }

    private val counts = combine(
        customRuleRepository.enabledRuleTexts.map { it.size },
        siteSettingsRepository.sites.map { it.size },
    ) { rules, sites -> rules to sites }

    /** Null until every source has emitted once (loading state). */
    val uiState: StateFlow<AdBlockDashboardUiState?> = combine(
        combine(appPreferences.adBlockEnabled, adBlockRepository.status, ::Pair),
        statsRepository.statistics,
        listsSummary,
        observeSchedule(filterLists),
        counts,
    ) { (enabled, status), statistics, lists, schedule, (rules, sites) ->
        AdBlockDashboardUiState(enabled, status, statistics, lists, schedule, rules, sites)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _updating = MutableStateFlow(false)

    /** A user-requested update is running. */
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _updateOutcome = MutableStateFlow<FilterUpdateOutcome?>(null)

    /** Result of the last user-requested update, until shown ([onUpdateOutcomeShown]). */
    val updateOutcome: StateFlow<FilterUpdateOutcome?> = _updateOutcome.asStateFlow()

    init {
        // Counters still in memory belong on this page.
        viewModelScope.launch { statsRepository.flushPending() }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAdBlockEnabled(enabled) }
    }

    fun setUpdateInterval(interval: FilterUpdateInterval) {
        viewModelScope.launch { appPreferences.setFilterUpdateIntervalHours(interval.hours) }
    }

    /** Runs in [viewModelScope]: survives configuration changes, unlike a composition scope. */
    fun updateNow() {
        if (_updating.value) return
        _updating.value = true
        viewModelScope.launch {
            try {
                _updateOutcome.value = updateFilterLists()
            } finally {
                _updating.value = false
            }
        }
    }

    fun onUpdateOutcomeShown() {
        _updateOutcome.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
