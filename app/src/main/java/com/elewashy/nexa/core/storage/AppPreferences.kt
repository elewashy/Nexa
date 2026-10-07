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

    /** Single source-of-truth snapshot for all small persisted user settings. */
    val settings: Flow<AppSettings>

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

    /**
     * Whether the first-launch onboarding was finished or skipped. Defaults to false; only
     * clearing the app's data resets it.
     */
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

    /** User-selected search engine for address-bar queries. Defaults to Google. */
    val selectedSearchEngine: Flow<Int>

    /**
     * Custom home page address (normalized http(s) URL), or null to follow the selected search
     * engine's home page. Defaults to null.
     */
    val homePageUrl: Flow<String?>

    /** Download Manager layout. Defaults to Media gallery. */
    val downloadManagerLayout: Flow<Int>

    /** Bookmarks order, stored by name; null until the user picks one. */
    val bookmarkSort: Flow<String?>

    /** Bookmarks layout, stored by name; null until the user picks one. */
    val bookmarkViewMode: Flow<String?>

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

    /** Marks the first-launch onboarding as done. One-way: nothing in the app clears it. */
    suspend fun setOnboardingCompleted()

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

    /** Updates [selectedSearchEngine]. Unknown values are sanitized by readers. */
    suspend fun setSelectedSearchEngine(engine: Int)

    /** Updates [homePageUrl]. Pass null to follow the search engine's home page again. */
    suspend fun setHomePageUrl(url: String?)

    /** Updates [downloadManagerLayout]. */
    suspend fun setDownloadManagerLayout(layout: Int)

    /** Updates [bookmarkSort]. */
    suspend fun setBookmarkSort(name: String)

    /** Updates [bookmarkViewMode]. */
    suspend fun setBookmarkViewMode(name: String)

    suspend fun setMaxConcurrentDownloads(value: Int)

    suspend fun setDownloadFilterIds(ids: Set<String>)

    suspend fun setDownloadSpeedLimitBytesPerSecond(value: Long)

    suspend fun setAutoRetryDownloads(enabled: Boolean)

    suspend fun setVisualVideoPresentation(enabled: Boolean)

    suspend fun setShowDownloadFilterCounts(show: Boolean)

    /** Automatic filter-list update interval in hours; 0 disables automatic updates. */
    val filterUpdateIntervalHours: Flow<Int>

    /** Updates [filterUpdateIntervalHours]. Unknown values are sanitized by readers. */
    suspend fun setFilterUpdateIntervalHours(hours: Int)

    /** Global content-blocking switch. Defaults to true. */
    val adBlockEnabled: Flow<Boolean>

    /** Updates [adBlockEnabled]. */
    suspend fun setAdBlockEnabled(enabled: Boolean)

    /** Whether custom filter rules may use trusted-only scriptlets. Defaults to false, like uBO. */
    val adBlockTrustCustomRules: Flow<Boolean>

    /** Updates [adBlockTrustCustomRules]. */
    suspend fun setAdBlockTrustCustomRules(trusted: Boolean)

}
