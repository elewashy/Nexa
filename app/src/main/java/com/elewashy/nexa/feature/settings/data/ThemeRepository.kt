package com.elewashy.nexa.feature.settings.data

import com.elewashy.nexa.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow

/**
 * Persists and observes the user's theme-mode preference.
 *
 * Dual-store design:
 *  - **SharedPreferences** — available for synchronous cold-start reads if a
 *    non-Compose caller needs them.
 *  - **DataStore (via [AppPreferences])** — exposed as a [Flow] so future
 *    consumers (e.g. a settings VM that wants to reflect external theme
 *    changes in real time) can collect without polling.
 *
 * The repository is the single write-point for theme; both stores are always
 * updated together in [setThemeMode].
 *
 * @see com.elewashy.nexa.core.storage.AppPreferences
 */
interface ThemeRepository {

    /** Reactive stream of the persisted theme mode. Backed by DataStore. */
    val themeMode: Flow<AppTheme>

    /**
     * Synchronous read of the persisted theme mode. Returns system theme mode
     * if no preference is stored.
     */
    fun getThemeModeSync(): Int

    /** Writes to both SharedPrefs (sync) and DataStore (suspend). */
    suspend fun setThemeMode(mode: Int)

    /** Convenience overload that accepts [AppTheme]. */
    suspend fun setThemeMode(theme: AppTheme)
}
