package com.elewashy.nexa.core.storage

import kotlinx.coroutines.flow.Flow

/**
 * Application-wide, type-safe preferences surface.
 *
 * Backed by Jetpack DataStore (Preferences). Replaces scattered direct use of
 * [android.content.SharedPreferences] for *small* user-facing settings where
 * async IO is acceptable.
 *
 * **Not** a blanket replacement for SharedPreferences everywhere: ad-block
 * metadata and download-persistence JSON stay on SharedPrefs in their current
 * locations because their hot paths require synchronous commits.
 *
 * All writes are suspending; all reads return a cold [Flow] that re-emits on
 * every mutation. Readers must run inside a coroutine scope.
 *
 * Theme cold start intentionally keeps a synchronous SharedPreferences seed in
 * `NexaApp`.
 */
interface AppPreferences {

    /** Persisted night-mode selection. Defaults to system theme. */
    val themeMode: Flow<Int>

    /** Whether to use Material You dynamic colors from the wallpaper (API 31+). Defaults to true. */
    val dynamicColor: Flow<Boolean>

    /** Whether to use a pure black background in dark mode. Defaults to false. */
    val pureBlack: Flow<Boolean>

    /** Whether app windows should avoid capping to 60 Hz on high-refresh displays. Defaults to true. */
    val highRefreshRate: Flow<Boolean>

    /** ARGB seed color used for generated Material color schemes. */
    val selectedThemeColor: Flow<Int>

    /** Whether the user has completed the first-launch onboarding flow. Defaults to false. */
    val onboardingCompleted: Flow<Boolean>

    /** Persisted app language tag, or null for system default. */
    val languageTag: Flow<String?>

    /** Whether to check for app updates on launch. Defaults to true. */
    val autoUpdateCheck: Flow<Boolean>

    /** Whether to show the update dialog on launch. Defaults to true. */
    val showUpdateDialogOnLaunch: Flow<Boolean>

    /** Updates [themeMode]. */
    suspend fun setThemeMode(mode: Int)

    /** Updates [dynamicColor]. */
    suspend fun setDynamicColor(enabled: Boolean)

    /** Updates [pureBlack]. */
    suspend fun setPureBlack(enabled: Boolean)

    /** Updates [highRefreshRate]. */
    suspend fun setHighRefreshRate(enabled: Boolean)

    /** Updates [selectedThemeColor]. */
    suspend fun setSelectedThemeColor(color: Int)

    /** Updates [onboardingCompleted]. */
    suspend fun setOnboardingCompleted(completed: Boolean)

    /** Updates [languageTag]. Pass null to follow the system language. */
    suspend fun setLanguageTag(tag: String?)

    /** Updates [autoUpdateCheck]. */
    suspend fun setAutoUpdateCheck(enabled: Boolean)

    /** Updates [showUpdateDialogOnLaunch]. */
    suspend fun setShowUpdateDialogOnLaunch(enabled: Boolean)

}
