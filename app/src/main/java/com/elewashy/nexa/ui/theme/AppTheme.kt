package com.elewashy.nexa.ui.theme

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
