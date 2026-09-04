package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.elewashy.nexa.core.theme.DEFAULT_THEME_COLOR_ARGB
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.downloads.domain.model.DownloadFilterCategory
import com.elewashy.nexa.feature.downloads.domain.model.DownloadSettingsDefaults
import com.elewashy.nexa.ui.theme.AppThemeMode
import com.elewashy.nexa.core.common.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore-backed implementation of [AppPreferences]. */
@Singleton
class DataStoreAppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : AppPreferences {

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs -> prefs.toAppSettings() }
        .distinctUntilChanged()

    init {
        appScope.launch {
            settings
                .map { ThemeSeedSnapshot(it.themeMode, it.dynamicColor, it.pureBlack, it.selectedThemeColor) }
                .distinctUntilChanged()
                .collect { snapshot -> snapshot.mirrorTo(context) }
        }
    }

    override val themeMode: Flow<Int> = settings.map { it.themeMode }.distinctUntilChanged()
    override val hasStoredThemeMode: Flow<Boolean> = settings.map { it.hasStoredThemeMode }.distinctUntilChanged()
    override val dynamicColor: Flow<Boolean> = settings.map { it.dynamicColor }.distinctUntilChanged()
    override val pureBlack: Flow<Boolean> = settings.map { it.pureBlack }.distinctUntilChanged()
    override val highRefreshRate: Flow<Boolean> = settings.map { it.highRefreshRate }.distinctUntilChanged()
    override val selectedThemeColor: Flow<Int> = settings.map { it.selectedThemeColor }.distinctUntilChanged()
    override val onboardingCompleted: Flow<Boolean> = settings.map { it.onboardingCompleted }.distinctUntilChanged()
    override val languageTag: Flow<String?> = settings.map { it.languageTag }.distinctUntilChanged()
    override val autoUpdateCheck: Flow<Boolean> = settings.map { it.autoUpdateCheck }.distinctUntilChanged()
    override val showUpdateDialogOnLaunch: Flow<Boolean> = settings.map { it.showUpdateDialogOnLaunch }.distinctUntilChanged()
    override val videoDownloadButton: Flow<Boolean> = settings.map { it.videoDownloadButton }.distinctUntilChanged()
    override val browserNavigationBarPosition: Flow<Int> = settings.map { it.browserNavigationBarPosition }.distinctUntilChanged()
    override val downloadManagerLayout: Flow<Int> = settings.map { it.downloadManagerLayout }.distinctUntilChanged()
    override val maxConcurrentDownloads: Flow<Int> = settings.map { it.maxConcurrentDownloads }.distinctUntilChanged()
    override val downloadFilterIds: Flow<Set<String>> = settings.map { it.downloadFilterIds }.distinctUntilChanged()
    override val downloadSpeedLimitBytesPerSecond: Flow<Long> = settings.map { it.downloadSpeedLimitBytesPerSecond }.distinctUntilChanged()
    override val autoRetryDownloads: Flow<Boolean> = settings.map { it.autoRetryDownloads }.distinctUntilChanged()
    override val visualVideoPresentation: Flow<Boolean> = settings.map { it.visualVideoPresentation }.distinctUntilChanged()
    override val showDownloadFilterCounts: Flow<Boolean> = settings.map { it.showDownloadFilterCounts }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: Int) {
        ThemeModeSeed.mirrorThemeMode(context, mode)
        dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        ThemeModeSeed.mirrorDynamicColor(context, enabled)
        dataStore.edit { prefs -> prefs[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setPureBlack(enabled: Boolean) {
        ThemeModeSeed.mirrorPureBlack(context, enabled)
        dataStore.edit { prefs -> prefs[KEY_PURE_BLACK] = enabled }
    }

    override suspend fun setHighRefreshRate(enabled: Boolean) {
        dataStore.edit { it[KEY_HIGH_REFRESH_RATE] = enabled }
    }

    override suspend fun setSelectedThemeColor(color: Int) {
        ThemeModeSeed.mirrorSelectedThemeColor(context, color)
        dataStore.edit { prefs -> prefs[KEY_SELECTED_THEME_COLOR] = color }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setLanguageTag(tag: String?) {
        dataStore.edit { prefs -> if (tag == null) prefs.remove(KEY_LANGUAGE_TAG) else prefs[KEY_LANGUAGE_TAG] = tag }
    }

    override suspend fun setAutoUpdateCheck(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_UPDATE_CHECK] = enabled }
    }

    override suspend fun setShowUpdateDialogOnLaunch(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_UPDATE_DIALOG_ON_LAUNCH] = enabled }
    }

    override suspend fun setVideoDownloadButton(enabled: Boolean) {
        dataStore.edit { it[KEY_VIDEO_DOWNLOAD_BUTTON] = enabled }
    }

    override suspend fun setBrowserNavigationBarPosition(position: Int) {
        dataStore.edit { it[KEY_BROWSER_NAVIGATION_BAR_POSITION] = BrowserNavigationBarPosition.fromStoredValue(position).storedValue }
    }

    override suspend fun setDownloadManagerLayout(layout: Int) {
        dataStore.edit { it[KEY_DOWNLOAD_MANAGER_LAYOUT] = layout }
    }

    override suspend fun setMaxConcurrentDownloads(value: Int) {
        dataStore.edit { it[KEY_MAX_CONCURRENT_DOWNLOADS] = DownloadSettingsDefaults.clampConcurrentDownloads(value) }
    }

    override suspend fun setDownloadFilterIds(ids: Set<String>) {
        val knownIds = DownloadFilterCategory.entries.mapTo(HashSet()) { it.storedId }
        dataStore.edit { it[KEY_DOWNLOAD_FILTER_IDS] = ids.filterTo(linkedSetOf()) { id -> id in knownIds } }
    }

    override suspend fun setDownloadSpeedLimitBytesPerSecond(value: Long) {
        dataStore.edit { it[KEY_DOWNLOAD_SPEED_LIMIT] = DownloadSettingsDefaults.sanitizeSpeedLimit(value) }
    }

    override suspend fun setAutoRetryDownloads(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_RETRY_DOWNLOADS] = enabled }
    }

    override suspend fun setVisualVideoPresentation(enabled: Boolean) {
        dataStore.edit { it[KEY_VISUAL_VIDEO_PRESENTATION] = enabled }
    }

    override suspend fun setShowDownloadFilterCounts(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_DOWNLOAD_FILTER_COUNTS] = show }
    }

    private data class ThemeSeedSnapshot(
        val themeMode: Int,
        val dynamicColor: Boolean,
        val pureBlack: Boolean,
        val selectedThemeColor: Int,
    ) {
        fun mirrorTo(context: Context) {
            ThemeModeSeed.mirrorThemeMode(context, themeMode)
            ThemeModeSeed.mirrorDynamicColor(context, dynamicColor)
            ThemeModeSeed.mirrorPureBlack(context, pureBlack)
            ThemeModeSeed.mirrorSelectedThemeColor(context, selectedThemeColor)
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val knownIds = DownloadFilterCategory.entries.mapTo(HashSet()) { it.storedId }
        return AppSettings(
            themeMode = this[KEY_THEME_MODE] ?: AppThemeMode.SYSTEM,
            hasStoredThemeMode = contains(KEY_THEME_MODE),
            dynamicColor = this[KEY_DYNAMIC_COLOR] ?: true,
            pureBlack = this[KEY_PURE_BLACK] ?: false,
            highRefreshRate = this[KEY_HIGH_REFRESH_RATE] ?: true,
            selectedThemeColor = this[KEY_SELECTED_THEME_COLOR] ?: DEFAULT_THEME_COLOR_ARGB,
            onboardingCompleted = this[KEY_ONBOARDING_COMPLETED] ?: false,
            languageTag = this[KEY_LANGUAGE_TAG],
            autoUpdateCheck = this[KEY_AUTO_UPDATE_CHECK] ?: true,
            showUpdateDialogOnLaunch = this[KEY_SHOW_UPDATE_DIALOG_ON_LAUNCH] ?: true,
            videoDownloadButton = this[KEY_VIDEO_DOWNLOAD_BUTTON] ?: true,
            browserNavigationBarPosition = BrowserNavigationBarPosition.fromStoredValue(
                this[KEY_BROWSER_NAVIGATION_BAR_POSITION] ?: BrowserNavigationBarPosition.Bottom.storedValue
            ).storedValue,
            downloadManagerLayout = this[KEY_DOWNLOAD_MANAGER_LAYOUT] ?: 0,
            maxConcurrentDownloads = DownloadSettingsDefaults.clampConcurrentDownloads(
                this[KEY_MAX_CONCURRENT_DOWNLOADS] ?: DownloadSettingsDefaults.DEFAULT_CONCURRENT_DOWNLOADS
            ),
            downloadFilterIds = (this[KEY_DOWNLOAD_FILTER_IDS] ?: DownloadSettingsDefaults.DEFAULT_FILTER_IDS)
                .filterTo(linkedSetOf()) { it in knownIds },
            downloadSpeedLimitBytesPerSecond = DownloadSettingsDefaults.sanitizeSpeedLimit(
                this[KEY_DOWNLOAD_SPEED_LIMIT] ?: DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND
            ),
            autoRetryDownloads = this[KEY_AUTO_RETRY_DOWNLOADS] ?: true,
            visualVideoPresentation = this[KEY_VISUAL_VIDEO_PRESENTATION] ?: true,
            showDownloadFilterCounts = this[KEY_SHOW_DOWNLOAD_FILTER_COUNTS] ?: true,
        )
    }

    companion object Keys {
        val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_PURE_BLACK = booleanPreferencesKey("pure_black")
        val KEY_HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        val KEY_SELECTED_THEME_COLOR = intPreferencesKey("selected_theme_color")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val KEY_AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val KEY_SHOW_UPDATE_DIALOG_ON_LAUNCH = booleanPreferencesKey("show_update_dialog_on_launch")
        val KEY_VIDEO_DOWNLOAD_BUTTON = booleanPreferencesKey("video_download_button")
        val KEY_BROWSER_NAVIGATION_BAR_POSITION = intPreferencesKey("browser_navigation_bar_position")
        val KEY_DOWNLOAD_MANAGER_LAYOUT = intPreferencesKey("download_manager_layout")
        val KEY_MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("download_max_concurrent")
        val KEY_DOWNLOAD_FILTER_IDS = stringSetPreferencesKey("download_filter_ids")
        val KEY_DOWNLOAD_SPEED_LIMIT = longPreferencesKey("download_speed_limit_bytes_per_second")
        val KEY_AUTO_RETRY_DOWNLOADS = booleanPreferencesKey("download_auto_retry")
        val KEY_VISUAL_VIDEO_PRESENTATION = booleanPreferencesKey("download_visual_video_presentation")
        val KEY_SHOW_DOWNLOAD_FILTER_COUNTS = booleanPreferencesKey("download_show_filter_counts")
    }
}
