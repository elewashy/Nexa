package com.elewashy.nexa.feature.update.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    private val managerUpdateRepository: ManagerUpdateRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

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
}
