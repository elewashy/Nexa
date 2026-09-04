package com.elewashy.nexa.core.storage

import kotlinx.coroutines.flow.Flow

/**
 * Application-wide, type-safe preferences surface.
 *
 * Backed by Jetpack DataStore (Preferences). Replaces scattered direct use of
 * [android.content.SharedPreferences] for *small* user-facing settings where
 * async IO is acceptable.
 *
 * **Not** a blanket replacement for every store: ad-block metadata stays on
 * SharedPreferences because its hot path requires synchronous commits, and
 * structured, growing data (downloads, tabs, bookmarks, history) lives in Room.
 *
 * All writes are suspending; all reads return a cold [Flow] that re-emits on
 * every mutation. Readers must run inside a coroutine scope.
 *
 * Theme cold start intentionally keeps a synchronous SharedPreferences seed
 * ([ThemeModeSeed]) that `ui/theme/Theme.kt` reads for the first frame.
 */
interface AppPreferences {

    /** Persisted night-mode selection. Defaults to system theme. */
    val themeMode: Flow<Int>

    /**
     * True once a theme mode has been explicitly persisted to DataStore.
     * Lets callers distinguish "never stored" from "stored as SYSTEM" and
     * bootstrap one-time migrations without clobbering the stored value.
     */
    val hasStoredThemeMode: Flow<Boolean>

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

    /** Whether the in-browser video download button is shown. Defaults to true. */
    val videoDownloadButton: Flow<Boolean>

    /** Compact-window browser toolbar position. Defaults to bottom. */
    val browserNavigationBarPosition: Flow<Int>

    /** Download Manager layout. Defaults to Media gallery. */
    val downloadManagerLayout: Flow<Int>

    /** Maximum files allowed to transfer concurrently. Defaults to 3. */
    val maxConcurrentDownloads: Flow<Int>

    /** User-enabled Media gallery category-filter IDs. */
    val downloadFilterIds: Flow<Set<String>>

    /** Aggregate download speed cap in bytes/second; zero means unlimited. */
    val downloadSpeedLimitBytesPerSecond: Flow<Long>

    /** Whether transient download failures are retried automatically. Defaults to true. */
    val autoRetryDownloads: Flow<Boolean>

    /** Whether completed videos use larger preview cards in either design. Defaults to true. */
    val visualVideoPresentation: Flow<Boolean>

    /** Whether category chips include item counts. Defaults to true. */
    val showDownloadFilterCounts: Flow<Boolean>

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

    /** Updates [videoDownloadButton]. */
    suspend fun setVideoDownloadButton(enabled: Boolean)

    /** Updates [browserNavigationBarPosition]. Unknown values are sanitized by readers. */
    suspend fun setBrowserNavigationBarPosition(position: Int)

    /** Updates [downloadManagerLayout]. */
    suspend fun setDownloadManagerLayout(layout: Int)

    suspend fun setMaxConcurrentDownloads(value: Int)

    suspend fun setDownloadFilterIds(ids: Set<String>)

    suspend fun setDownloadSpeedLimitBytesPerSecond(value: Long)

    suspend fun setAutoRetryDownloads(enabled: Boolean)

    suspend fun setVisualVideoPresentation(enabled: Boolean)

    suspend fun setShowDownloadFilterCounts(show: Boolean)

}
