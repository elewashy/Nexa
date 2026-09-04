package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.core.content.edit
import com.elewashy.nexa.core.theme.DEFAULT_THEME_COLOR_ARGB
import com.elewashy.nexa.ui.theme.AppThemeMode

/**
 * Synchronous cold-start mirror for the small subset of preferences that affect the app theme.
 *
 * Jetpack DataStore remains the source of truth for persisted settings. These values are mirrored
 * to SharedPreferences on every theme write so the first Compose frame can use the last selected
 * appearance without blocking the main thread on DataStore IO.
 */
object ThemeModeSeed {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_PURE_BLACK = "pure_black"
    private const val KEY_SELECTED_THEME_COLOR = "selected_theme_color"

    fun read(context: Context): Int = readThemeSettings(context).themeMode

    fun readThemeSettings(context: Context): AppSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppSettings(
            themeMode = prefs.getInt(KEY_NIGHT_MODE, AppThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
            selectedThemeColor = prefs.getInt(KEY_SELECTED_THEME_COLOR, DEFAULT_THEME_COLOR_ARGB),
        )
    }

    fun write(context: Context, mode: Int) = mirrorThemeMode(context, mode)

    fun mirrorThemeMode(context: Context, mode: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_NIGHT_MODE, mode) }
    }

    fun mirrorDynamicColor(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    fun mirrorPureBlack(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PURE_BLACK, enabled) }
    }

    fun mirrorSelectedThemeColor(context: Context, color: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_SELECTED_THEME_COLOR, color) }
    }
}
