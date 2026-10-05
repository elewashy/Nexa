package com.elewashy.nexa.feature.update.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import com.elewashy.nexa.feature.update.domain.usecase.CheckForUpdateOnLaunchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Launch-time update check and the "update available" dialog over the browser. Scoped to the
 * browser Activity, so the check runs once per launch (not per configuration change).
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    managerUpdateRepository: ManagerUpdateRepository,
    private val appPreferences: AppPreferences,
    checkForUpdateOnLaunch: CheckForUpdateOnLaunchUseCase,
) : ViewModel() {

    init {
        viewModelScope.launch {
            // Leave startup CPU and network to the first page load.
            delay(LAUNCH_CHECK_DELAY_MS)
            checkForUpdateOnLaunch()
        }
    }

    private val _dialogDismissed = MutableStateFlow(false)

    val hasUpdate: StateFlow<Boolean> = managerUpdateRepository.hasUpdate

    val version: StateFlow<String?> = managerUpdateRepository.version

    val showUpdateDialog: StateFlow<Boolean> = combine(
        managerUpdateRepository.hasUpdate,
        appPreferences.showUpdateDialogOnLaunch,
        appPreferences.autoUpdateCheck,
        _dialogDismissed,
    ) { hasUpdate, showDialogPref, autoCheck, dismissed ->
        hasUpdate && showDialogPref && autoCheck && !dismissed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissDialog() {
        _dialogDismissed.value = true
    }

    fun setShowUpdateDialogOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setShowUpdateDialogOnLaunch(enabled)
        }
    }

    private companion object {
        const val LAUNCH_CHECK_DELAY_MS = 3_000L
    }
}
