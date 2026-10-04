package com.elewashy.nexa.feature.update.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.R
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.update.data.GitHubRateLimitedException
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App updates only. Filter-list updates are managed on the Ad blocker page
 * (`feature.adblock.presentation`).
 */
@HiltViewModel
class UpdatesSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val managerUpdateRepository: ManagerUpdateRepository,
    private val appPreferences: AppPreferences,
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
}
