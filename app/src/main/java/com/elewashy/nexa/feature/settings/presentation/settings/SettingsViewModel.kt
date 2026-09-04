package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.display.RefreshRateManager
import com.elewashy.nexa.core.localization.AppLanguage
import com.elewashy.nexa.core.localization.AppLanguageManager
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.theme.DEFAULT_THEME_COLOR_ARGB
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.settings.data.ThemeRepository
import com.elewashy.nexa.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val themeRepository: ThemeRepository,
    refreshRateManager: RefreshRateManager,
) : ViewModel() {

    // ── Theme ────────────────────────────────────────────────────────────

    val theme: StateFlow<AppTheme> = themeRepository.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppTheme.SYSTEM,
        )

    val dynamicColor: StateFlow<Boolean> = appPreferences.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pureBlack: StateFlow<Boolean> = appPreferences.pureBlack
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val highRefreshRate: StateFlow<Boolean> = appPreferences.highRefreshRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val videoDownloadButton: StateFlow<Boolean> = appPreferences.videoDownloadButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val browserNavigationBarPosition: StateFlow<BrowserNavigationBarPosition> =
        appPreferences.browserNavigationBarPosition
            .map(BrowserNavigationBarPosition::fromStoredValue)
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                BrowserNavigationBarPosition.Bottom,
            )

    val selectedThemeColor: StateFlow<Int> = appPreferences.selectedThemeColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_THEME_COLOR_ARGB)

    val highRefreshRateSupported: Boolean = refreshRateManager.isHighRefreshRateSupported()

    private val _currentLanguage = MutableStateFlow(AppLanguageManager.currentLanguage())
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.languageTag
                .distinctUntilChanged()
                .collect { tag ->
                    val language = AppLanguageManager.fromTag(tag)
                    _currentLanguage.value = language
                    // AppCompatDelegate already auto-restores stored locales
                    // (autoStoreLocales), so only apply a tag that genuinely
                    // differs from the one already applied — avoids a redundant
                    // setApplicationLocales and the activity recreation it
                    // triggers on every ViewModel creation.
                    if (AppLanguageManager.currentLanguage() != language) {
                        AppLanguageManager.setLanguageTag(tag)
                    }
                }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { themeRepository.setThemeMode(theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setDynamicColor(enabled) }
    }

    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setPureBlack(enabled) }
    }

    fun setHighRefreshRate(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setHighRefreshRate(enabled) }
    }

    fun setVideoDownloadButton(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setVideoDownloadButton(enabled) }
    }

    fun setBrowserNavigationBarPosition(position: BrowserNavigationBarPosition) {
        viewModelScope.launch { appPreferences.setBrowserNavigationBarPosition(position.storedValue) }
    }

    fun setSelectedThemeColor(color: Int) {
        viewModelScope.launch {
            appPreferences.setSelectedThemeColor(color)
            appPreferences.setDynamicColor(color == DEFAULT_THEME_COLOR_ARGB)
        }
    }

    fun setLanguage(language: AppLanguage, onApplied: (() -> Unit)? = null) {
        val wasAlreadySelected = _currentLanguage.value == language
        _currentLanguage.value = language
        viewModelScope.launch {
            if (!wasAlreadySelected) {
                appPreferences.setLanguageTag(language.tag)
            }
            AppLanguageManager.setLanguage(language)
            onApplied?.invoke()
        }
    }
}
