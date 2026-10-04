package com.elewashy.nexa.feature.adblock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicy
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicyStore
import com.elewashy.nexa.feature.adblock.data.SiteSettingsRepository
import com.elewashy.nexa.feature.adblock.domain.usecase.SetSiteAdBlockingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Ad blocking for the page in the active tab, as shown in the browser's
 * More options menu.
 *
 * @property site the site the switch changes: the page's site, or the
 *   parent domain whose setting currently governs it.
 * @property globalEnabled false = the ad blocker is off everywhere; the switch is then unavailable.
 * @property siteEnabled whether this page is filtered.
 */
data class SiteAdBlockMenuState(
    val site: String,
    val globalEnabled: Boolean,
    val siteEnabled: Boolean,
)

/** Result of the menu switch, shown once; the page is reloaded so the change applies. */
data class SiteAdBlockChanged(val site: String, val enabled: Boolean)

@HiltViewModel
class SiteAdBlockMenuViewModel @Inject constructor(
    policyStore: AdBlockPolicyStore,
    private val setSiteAdBlocking: SetSiteAdBlockingUseCase,
) : ViewModel() {

    private val pageUrl = MutableStateFlow<String?>(null)

    /** Null for pages that are not web sites (start page, files, data URLs). */
    val state: StateFlow<SiteAdBlockMenuState?> = combine(pageUrl, policyStore.policy, ::menuState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _changes = Channel<SiteAdBlockChanged>(Channel.BUFFERED)

    /** One event per completed switch change, after the engine sees it. */
    val changes: Flow<SiteAdBlockChanged> = _changes.receiveAsFlow()

    /** The active tab's committed URL. */
    fun onPageUrlChanged(url: String?) {
        pageUrl.value = url
    }

    fun setSiteEnabled(enabled: Boolean) {
        val url = pageUrl.value ?: return
        viewModelScope.launch {
            val site = setSiteAdBlocking(url, enabled) ?: return@launch
            _changes.send(SiteAdBlockChanged(site, enabled))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        internal fun menuState(url: String?, policy: AdBlockPolicy): SiteAdBlockMenuState? {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null
            val pageSite = SiteSettingsRepository.siteKey(url) ?: return null
            val governing = policy.governingSettings(pageSite)
            return SiteAdBlockMenuState(
                site = governing?.host ?: pageSite,
                globalEnabled = policy.globalEnabled,
                siteEnabled = policy.forUrl(url).filtering,
            )
        }
    }
}
