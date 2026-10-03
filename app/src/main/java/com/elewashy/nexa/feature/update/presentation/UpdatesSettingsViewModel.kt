package com.elewashy.nexa.feature.update.presentation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.R
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.storage.FilterTimestampStore
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockStatus
import com.elewashy.nexa.feature.browser.data.adblock.FilterUpdateResult
import com.elewashy.nexa.feature.browser.domain.model.FilterUpdateInterval
import com.elewashy.nexa.feature.update.data.GitHubRateLimitedException
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdatesSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val managerUpdateRepository: ManagerUpdateRepository,
    private val appPreferences: AppPreferences,
    private val adBlockRepository: AdBlockRepository,
    filterTimestampStore: FilterTimestampStore,
) : ViewModel() {

    val managerVersion: StateFlow<String?> = managerUpdateRepository.version
    val hasUpdate: StateFlow<Boolean> = managerUpdateRepository.hasUpdate
    val updateReleasedAt = managerUpdateRepository.releasedAt

    val preferencesState: StateFlow<UpdatesPreferencesState?> = appPreferences.settings
        .map { settings ->
            UpdatesPreferencesState(
                autoUpdateCheck = settings.autoUpdateCheck,
                showUpdateDialogOnLaunch = settings.showUpdateDialogOnLaunch,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastFiltersUpdateTime: StateFlow<Long> = filterTimestampStore.lastUpdate

    /** Active filter engine state (rule counts, lists, update phase). */
    val adBlockStatus: StateFlow<AdBlockStatus> = adBlockRepository.status

    val filterUpdateInterval: StateFlow<FilterUpdateInterval?> = appPreferences.filterUpdateIntervalHours
        .map { FilterUpdateInterval.fromStoredValue(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun checkUpdates(): CheckUpdateResult {
        return try {
            if (managerUpdateRepository.getUpdateOrNull(refetch = true) != null) {
                CheckUpdateResult.UpdateAvailable
            } else {
                CheckUpdateResult.UpToDate
            }
        } catch (e: GitHubRateLimitedException) {
            // GitHub 403/429 (unauthenticated limit). Surface a friendly,
            // actionable message instead of a generic failure.
            CheckUpdateResult.RateLimited(
                appContext.getString(R.string.github_rate_limit_reached)
            )
        } catch (e: Exception) {
            CheckUpdateResult.Failed
        }
    }

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoUpdateCheck(enabled) }
    }

    fun setShowUpdateDialogOnLaunch(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setShowUpdateDialogOnLaunch(enabled) }
    }

    /** The automatic update loop observes this setting and reschedules itself. */
    fun setFilterUpdateInterval(interval: FilterUpdateInterval) {
        viewModelScope.launch { appPreferences.setFilterUpdateIntervalHours(interval.hours) }
    }

    /** Checks every filter list now (conditional requests), recompiling if anything changed. */
    suspend fun updateAllFilters(): FilterUpdateResult {
        val interval = FilterUpdateInterval.fromStoredValue(appPreferences.filterUpdateIntervalHours.first())
        return try {
            adBlockRepository.refresh(interval.intervalMs, force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Filter update failed", e)
            FilterUpdateResult(checked = 0, updated = 0, failed = 1)
        }
    }

    data class UpdatesPreferencesState(
        val autoUpdateCheck: Boolean,
        val showUpdateDialogOnLaunch: Boolean,
    )

    sealed interface CheckUpdateResult {
        data object UpdateAvailable : CheckUpdateResult
        data object UpToDate : CheckUpdateResult
        data object Failed : CheckUpdateResult

        /** GitHub rejected the check with 403/429; [message] is user-facing. */
        data class RateLimited(val message: String) : CheckUpdateResult
    }

    private companion object {
        const val TAG = "UpdatesSettingsVM"
    }
}
