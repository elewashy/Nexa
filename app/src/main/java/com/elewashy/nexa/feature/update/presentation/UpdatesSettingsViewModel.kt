package com.elewashy.nexa.feature.update.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.storage.FilterTimestampStore
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.links.ValidLinkRepository
import com.elewashy.nexa.feature.browser.data.scripts.ScriptRepository
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UpdatesSettingsViewModel @Inject constructor(
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

    enum class CheckUpdateResult {
        UpdateAvailable,
        UpToDate,
        Failed,
    }
}
