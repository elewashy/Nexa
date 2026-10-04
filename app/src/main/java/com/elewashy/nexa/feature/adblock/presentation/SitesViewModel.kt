package com.elewashy.nexa.feature.adblock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicyStore
import com.elewashy.nexa.feature.adblock.data.SiteSettingsRepository
import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SitesMessage {
    data class Reset(val host: String) : SitesMessage
}

@HiltViewModel
class SitesViewModel @Inject constructor(
    private val policyStore: AdBlockPolicyStore,
    siteSettingsRepository: SiteSettingsRepository,
) : ViewModel() {

    /** Sites with custom settings; null while loading. */
    val sites: StateFlow<List<SiteAdBlockSettings>?> = siteSettingsRepository.sites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<SitesMessage?>(null)
    val message: StateFlow<SitesMessage?> = _message.asStateFlow()

    /** Adds [input] with ad blocking off; returns false when it is not a valid site. */
    fun add(input: String): Boolean {
        val host = SiteSettingsRepository.siteKey(input) ?: return false
        val existing = sites.value?.firstOrNull { it.host == host }
        viewModelScope.launch {
            policyStore.saveSite((existing ?: SiteAdBlockSettings(host)).copy(blockingEnabled = false))
        }
        return true
    }

    fun save(settings: SiteAdBlockSettings) {
        viewModelScope.launch { policyStore.saveSite(settings) }
    }

    fun reset(host: String) {
        viewModelScope.launch {
            policyStore.removeSite(host)
            _message.value = SitesMessage.Reset(host)
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
