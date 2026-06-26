package com.elewashy.nexa.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.graphics.toArgb
import com.elewashy.nexa.ui.theme.AppThemeMode
import com.elewashy.nexa.ui.theme.DefaultThemeColor
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
        prefs[KEY_SELECTED_THEME_COLOR] ?: DefaultThemeColor.toArgb()
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
    }
}
