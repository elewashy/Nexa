package com.elewashy.nexa.core.theme

/**
 * ARGB value of the default theme seed color (Nexa blue, `0xFF0061A7` —
 * the same value as `ui/theme`'s `DefaultThemeColor`/`NexaBlue`).
 *
 * Defined here as a plain Int so non-UI layers (core storage defaults,
 * ViewModels) never need to depend on Compose's `Color`/`toArgb`.
 */
const val DEFAULT_THEME_COLOR_ARGB: Int = 0xFF0061A7.toInt()
