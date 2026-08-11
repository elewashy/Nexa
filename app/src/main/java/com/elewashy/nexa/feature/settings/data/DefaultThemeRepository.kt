package com.elewashy.nexa.feature.settings.data

import android.content.Context
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.storage.ThemeModeSeed
import com.elewashy.nexa.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ThemeRepository] backed by [AppPreferences] (DataStore) for the
 * reactive flow plus a `theme_prefs` SharedPreferences seed
 * ([ThemeModeSeed]) for the synchronous cold-start read.
 *
 * DataStore stays the source of truth; the seed only prevents a theme flash
 * on the first composed frame while DataStore is still loading.
 */
@Singleton
class DefaultThemeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ThemeRepository {

    override val themeMode: Flow<AppTheme> = appPreferences.themeMode.map(AppTheme::fromPreferenceValue)

    init {
        appScope.launch {
            // One-time migration only: seed DataStore from the SharedPrefs
            // seed when DataStore has no value yet (pre-DataStore installs).
            // Writing unconditionally inverted the direction and let a stale
            // seed clobber the source of truth.
            if (!appPreferences.hasStoredThemeMode.first()) {
                appPreferences.setThemeMode(getThemeModeSync())
            }
        }
    }

    override fun getThemeModeSync(): Int = ThemeModeSeed.read(context)

    override suspend fun setThemeMode(theme: AppTheme) {
        setThemeMode(theme.preferenceValue)
    }

    override suspend fun setThemeMode(mode: Int) {
        // Seed first (synchronous, cold-start critical); then DataStore, the
        // source of truth (suspend; survives caller cancellation via caller's scope).
        ThemeModeSeed.write(context, mode)
        appPreferences.setThemeMode(mode)
    }
}
