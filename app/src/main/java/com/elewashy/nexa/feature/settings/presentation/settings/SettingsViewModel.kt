package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.toArgb
import com.elewashy.nexa.core.display.RefreshRateManager
import com.elewashy.nexa.core.localization.AppLanguage
import com.elewashy.nexa.core.localization.AppLanguageManager
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.settings.data.ThemeRepository
import com.elewashy.nexa.ui.theme.AppTheme
import com.elewashy.nexa.ui.theme.DefaultThemeColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val selectedThemeColor: StateFlow<Int> = appPreferences.selectedThemeColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultThemeColor.toArgb())

    val highRefreshRateSupported: Boolean = refreshRateManager.isHighRefreshRateSupported()

    private val _currentLanguage = MutableStateFlow(AppLanguageManager.currentLanguage())
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.languageTag
                .distinctUntilChanged()
                .collect { tag ->
                    _currentLanguage.value = AppLanguageManager.fromTag(tag)
                    AppLanguageManager.setLanguageTag(tag)
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

    fun setSelectedThemeColor(color: Int) {
        viewModelScope.launch {
            appPreferences.setSelectedThemeColor(color)
            appPreferences.setDynamicColor(color == DefaultThemeColor.toArgb())
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
