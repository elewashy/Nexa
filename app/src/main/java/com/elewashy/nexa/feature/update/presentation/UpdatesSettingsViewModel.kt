package com.elewashy.nexa.feature.update.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.R
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.storage.FilterTimestampStore
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.links.ValidLinkRepository
import com.elewashy.nexa.feature.browser.data.scripts.ScriptRepository
import com.elewashy.nexa.feature.update.data.GitHubRateLimitedException
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UpdatesSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val managerUpdateRepository: ManagerUpdateRepository,
    private val appPreferences: AppPreferences,
    private val adBlockRepository: AdBlockRepository,
    private val validLinkRepository: ValidLinkRepository,
    private val scriptRepository: ScriptRepository,
    private val filterTimestampStore: FilterTimestampStore,
) : ViewModel() {

    val managerVersion: StateFlow<String?> = managerUpdateRepository.version
    val hasUpdate: StateFlow<Boolean> = managerUpdateRepository.hasUpdate
    val updateReleasedAt = managerUpdateRepository.releasedAt

    val autoUpdateCheck: StateFlow<Boolean> = appPreferences.autoUpdateCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val showUpdateDialogOnLaunch: StateFlow<Boolean> = appPreferences.showUpdateDialogOnLaunch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val lastFiltersUpdateTime: StateFlow<Long> = filterTimestampStore.lastUpdate

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

    suspend fun updateAllFilters(): FilterUpdateResult = withContext(Dispatchers.IO) {
        val adBlockSuccess = runCatching { adBlockRepository.updateAllAdBlockLists() }.getOrDefault(false)
        val validLinksSuccess = runCatching { validLinkRepository.updateValidLinks() }.getOrDefault(false)
        val scriptsSuccess = runCatching { scriptRepository.forceUpdateAll() }.isSuccess
        val success = adBlockSuccess && validLinksSuccess && scriptsSuccess
        if (success) filterTimestampStore.save()

        FilterUpdateResult(
            adBlockSuccess = adBlockSuccess,
            validLinksSuccess = validLinksSuccess,
            scriptsSuccess = scriptsSuccess,
        )
    }

    data class FilterUpdateResult(
        val adBlockSuccess: Boolean,
        val validLinksSuccess: Boolean,
        val scriptsSuccess: Boolean,
    ) {
        val success: Boolean = adBlockSuccess && validLinksSuccess && scriptsSuccess
    }

    sealed interface CheckUpdateResult {
        data object UpdateAvailable : CheckUpdateResult
        data object UpToDate : CheckUpdateResult
        data object Failed : CheckUpdateResult

        /** GitHub rejected the check with 403/429; [message] is user-facing. */
        data class RateLimited(val message: String) : CheckUpdateResult
    }
}
