package com.elewashy.nexa.core.storage

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [AppPreferences].
 *
 * The [DataStore] instance is supplied by Hilt (see `core.di.StorageModule`) so
 * tests can substitute an in-memory `DataStore<Preferences>` without mocking
 * Android framework classes.
 */
@Singleton
class DataStoreAppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : AppPreferences {

    override val themeMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: AppThemeMode.SYSTEM
    }

    override val hasStoredThemeMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs.contains(KEY_THEME_MODE)
    }

    override val dynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: true
    }

    override val pureBlack: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PURE_BLACK] ?: false
    }

    override val highRefreshRate: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HIGH_REFRESH_RATE] ?: true
    }

    override val selectedThemeColor: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_THEME_COLOR] ?: DEFAULT_THEME_COLOR_ARGB
    }

    override val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    override val languageTag: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE_TAG]
    }

    override val autoUpdateCheck: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_CHECK] ?: true
    }

    override val showUpdateDialogOnLaunch: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_UPDATE_DIALOG_ON_LAUNCH] ?: true
    }

    override val videoDownloadButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VIDEO_DOWNLOAD_BUTTON] ?: true
    }

    override val browserNavigationBarPosition: Flow<Int> = dataStore.data.map { prefs ->
        BrowserNavigationBarPosition.fromStoredValue(
            prefs[KEY_BROWSER_NAVIGATION_BAR_POSITION] ?: BrowserNavigationBarPosition.Bottom.storedValue
        ).storedValue
    }

    override val downloadManagerLayout: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_MANAGER_LAYOUT] ?: 0
    }

    override val maxConcurrentDownloads: Flow<Int> = dataStore.data.map { prefs ->
        DownloadSettingsDefaults.clampConcurrentDownloads(
            prefs[KEY_MAX_CONCURRENT_DOWNLOADS]
                ?: DownloadSettingsDefaults.DEFAULT_CONCURRENT_DOWNLOADS
        )
    }

    override val downloadFilterIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        val knownIds = DownloadFilterCategory.entries.mapTo(HashSet()) { it.storedId }
        (prefs[KEY_DOWNLOAD_FILTER_IDS] ?: DownloadSettingsDefaults.DEFAULT_FILTER_IDS)
            .filterTo(linkedSetOf()) { it in knownIds }
    }

    override val downloadSpeedLimitBytesPerSecond: Flow<Long> = dataStore.data.map { prefs ->
        DownloadSettingsDefaults.sanitizeSpeedLimit(
            prefs[KEY_DOWNLOAD_SPEED_LIMIT] ?: DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND
        )
    }

    override val autoRetryDownloads: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RETRY_DOWNLOADS] ?: true
    }

    override val visualVideoPresentation: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VISUAL_VIDEO_PRESENTATION] ?: true
    }

    override val showDownloadFilterCounts: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_DOWNLOAD_FILTER_COUNTS] ?: true
    }

    override suspend fun setThemeMode(mode: Int) {
        dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setPureBlack(enabled: Boolean) {
        dataStore.edit { it[KEY_PURE_BLACK] = enabled }
    }

    override suspend fun setHighRefreshRate(enabled: Boolean) {
        dataStore.edit { it[KEY_HIGH_REFRESH_RATE] = enabled }
    }

    override suspend fun setSelectedThemeColor(color: Int) {
        dataStore.edit { it[KEY_SELECTED_THEME_COLOR] = color }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setLanguageTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag == null) prefs.remove(KEY_LANGUAGE_TAG) else prefs[KEY_LANGUAGE_TAG] = tag
        }
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
        val sanitized = BrowserNavigationBarPosition.fromStoredValue(position).storedValue
        dataStore.edit { it[KEY_BROWSER_NAVIGATION_BAR_POSITION] = sanitized }
    }

    override suspend fun setDownloadManagerLayout(layout: Int) {
        dataStore.edit { it[KEY_DOWNLOAD_MANAGER_LAYOUT] = layout }
    }

    override suspend fun setMaxConcurrentDownloads(value: Int) {
        dataStore.edit {
            it[KEY_MAX_CONCURRENT_DOWNLOADS] = DownloadSettingsDefaults.clampConcurrentDownloads(value)
        }
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

    private companion object {
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
