package com.elewashy.nexa.ui.theme

import android.app.UiModeManager

/** User-selectable theme mode for the app. */
enum class AppTheme(val preferenceValue: Int) {
    SYSTEM(AppThemeMode.SYSTEM),
    LIGHT(AppThemeMode.LIGHT),
    DARK(AppThemeMode.DARK);

    companion object {
        fun fromPreferenceValue(value: Int): AppTheme = when (value) {
            AppThemeMode.LIGHT -> LIGHT
            AppThemeMode.DARK -> DARK
            else -> SYSTEM
        }
    }
}

/** The matching [UiModeManager] night mode; [AppTheme.SYSTEM] follows the device setting. */
fun AppTheme.toUiModeNightMode(): Int = when (this) {
    AppTheme.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    AppTheme.LIGHT -> UiModeManager.MODE_NIGHT_NO
    AppTheme.DARK -> UiModeManager.MODE_NIGHT_YES
}
