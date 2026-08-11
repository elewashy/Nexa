package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.core.content.edit
import com.elewashy.nexa.ui.theme.AppThemeMode

/**
 * Synchronous cold-start seed for the theme mode.
 *
 * DataStore is the source of truth for the theme mode, but it is async:
 * without a synchronous seed, the first composed frame falls back to
 * [AppThemeMode.SYSTEM] and flashes when the DataStore value arrives.
 *
 * The value is written here on every theme change (see
 * `DefaultThemeRepository`) and read synchronously as the initial Compose
 * state (see `ui/theme/Theme.kt`).
 *
 * The SharedPrefs names (`theme_prefs` / `night_mode`) and integer values are
 * the historical pre-DataStore keys, kept stable so existing installs migrate
 * without a version bump.
 */
object ThemeModeSeed {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"

    fun read(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppThemeMode.SYSTEM)

    /**
     * Writes with apply(): the edit is staged in-memory immediately, so a
     * same-process read right after a theme change already sees the new value.
     */
    fun write(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_NIGHT_MODE, mode) }
    }
}
