package com.elewashy.nexa.feature.settings.data

import android.content.Context
import androidx.core.content.edit
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.ui.theme.AppTheme
import com.elewashy.nexa.ui.theme.AppThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ThemeRepository] backed by [AppPreferences] (DataStore) for the
 * reactive flow plus a `theme_prefs` SharedPreferences file for the
 * synchronous cold-start read.
 *
 * The SharedPrefs key names (`theme_prefs` / `night_mode`) are identical to
 * the pre-refactor keys that `NexaApp.applyPersistedNightMode` and
 * `ThemeActivity` used. The persisted integer values are intentionally kept
 * compatible with the old AppCompat values so existing users' saved themes
 * migrate without a version bump.
 */
@Singleton
class DefaultThemeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ThemeRepository {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override val themeMode: Flow<AppTheme> = appPreferences.themeMode.map(AppTheme::fromPreferenceValue)

    init {
        appScope.launch {
            appPreferences.setThemeMode(getThemeModeSync())
        }
    }

    override fun getThemeModeSync(): Int =
        prefs.getInt(KEY_NIGHT_MODE, AppThemeMode.SYSTEM)

    override suspend fun setThemeMode(theme: AppTheme) {
        setThemeMode(theme.preferenceValue)
    }

    override suspend fun setThemeMode(mode: Int) {
        // Write SharedPrefs first (synchronous, cold-start critical) — on the
        // calling thread. `apply()` is non-blocking; the edit is staged
        // in-memory immediately so a same-process read sees it right away.
        prefs.edit { putInt(KEY_NIGHT_MODE, mode) }
        // Then DataStore (suspend; survives caller cancellation via caller's scope).
        appPreferences.setThemeMode(mode)
    }

    private companion object {
        const val PREFS_NAME = "theme_prefs"
        const val KEY_NIGHT_MODE = "night_mode"
    }
}
