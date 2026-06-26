package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the unified filters-last-updated timestamp.
 *
 * Written by [com.elewashy.nexa.feature.splash.domain.usecase.InitializeBlocklistsUseCase]
 * after every successful splash-screen filter refresh, and by
 * [com.elewashy.nexa.feature.settings.presentation.settings.SettingsViewModel]
 * after a manual refresh from the Updates settings page.
 *
 * Exposes a [StateFlow] so the Updates page re-renders instantly without
 * polling.
 */
@Singleton
class FilterTimestampStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _lastUpdate = MutableStateFlow(read())
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    /** Returns the stored timestamp, or 0 if never saved. */
    fun read(): Long = prefs.getLong(KEY_LAST_UPDATE, 0L)

    /** Returns true if at least [intervalMs] have elapsed since [read]. */
    fun isDue(intervalMs: Long = INTERVAL_6H_MS): Boolean =
        System.currentTimeMillis() - read() >= intervalMs

    /** Persists [now] and emits to [lastUpdate]. */
    fun save(now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_LAST_UPDATE, now) }
        _lastUpdate.value = now
    }

    companion object {
        const val PREFS_NAME = "FilterUpdateTimes"
        const val KEY_LAST_UPDATE = "unifiedLastUpdate"
        const val INTERVAL_6H_MS = 6 * 60 * 60 * 1000L
    }
}
