package com.elewashy.nexa.core.storage

import com.elewashy.nexa.core.theme.DEFAULT_THEME_COLOR_ARGB
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateInterval
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.downloads.domain.model.DownloadSettingsDefaults
import com.elewashy.nexa.ui.theme.AppThemeMode

/** Immutable snapshot of all small persisted user preferences. */
data class AppSettings(
    val themeMode: Int = AppThemeMode.SYSTEM,
    val hasStoredThemeMode: Boolean = false,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val highRefreshRate: Boolean = true,
    val selectedThemeColor: Int = DEFAULT_THEME_COLOR_ARGB,
    val onboardingCompleted: Boolean = false,
    val languageTag: String? = null,
    val autoUpdateCheck: Boolean = true,
    val showUpdateDialogOnLaunch: Boolean = true,
    val videoDownloadButton: Boolean = true,
    val browserNavigationBarPosition: Int = BrowserNavigationBarPosition.Bottom.storedValue,
    val selectedSearchEngine: Int = SearchEngine.DEFAULT.storedValue,
    val downloadManagerLayout: Int = 0,
    val maxConcurrentDownloads: Int = DownloadSettingsDefaults.DEFAULT_CONCURRENT_DOWNLOADS,
    val downloadFilterIds: Set<String> = DownloadSettingsDefaults.DEFAULT_FILTER_IDS,
    val downloadSpeedLimitBytesPerSecond: Long = DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND,
    val autoRetryDownloads: Boolean = true,
    val visualVideoPresentation: Boolean = true,
    val showDownloadFilterCounts: Boolean = true,
    /** Automatic filter-list update interval in hours ([FilterUpdateInterval]); 0 = manual only. */
    val filterUpdateIntervalHours: Int = FilterUpdateInterval.DEFAULT.hours,
    /** Global content-blocking switch. Defaults to on. */
    val adBlockEnabled: Boolean = true,
    /** Whether custom rules may use trusted-only scriptlets (uBO "Allow custom filters requiring trust"). */
    val adBlockTrustCustomRules: Boolean = false,
)
