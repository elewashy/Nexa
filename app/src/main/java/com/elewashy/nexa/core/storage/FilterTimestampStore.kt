package com.elewashy.nexa.core.storage

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.elewashy.nexa.core.common.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** DataStore-backed source of truth for the unified filters update timestamp. */
@Singleton
class FilterTimestampStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    init {
        appScope.launch {
            try {
                migrateLegacy()
                dataStore.data.collect { _lastUpdate.value = it[LAST_UPDATE] ?: 0L }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the legacy key so a later process can retry.
                Log.e(TAG, "Filter timestamp migration failed", e)
            }
        }
    }

    /** Persists [now] before the caller reports a successful filter refresh. */
    suspend fun save(now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[LAST_UPDATE] = now }
        _lastUpdate.value = now
    }

    /**
     * One-time SharedPreferences migration. The DataStore value is
     * authoritative when already present. Retirement happens only after the
     * DataStore transaction commits, making process death between steps safe.
     */
    suspend fun migrateLegacy() {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!legacy.contains(LEGACY_KEY_LAST_UPDATE)) return
        val legacyValue = legacy.getLong(LEGACY_KEY_LAST_UPDATE, 0L)
        var effectiveValue = 0L
        dataStore.edit { preferences ->
            if (preferences[LAST_UPDATE] == null) preferences[LAST_UPDATE] = legacyValue
            effectiveValue = preferences[LAST_UPDATE] ?: 0L
        }
        _lastUpdate.value = effectiveValue
        legacy.edit { remove(LEGACY_KEY_LAST_UPDATE) }
    }

    companion object {
        private const val TAG = "FilterTimestampStore"
        const val LEGACY_PREFS_NAME = "FilterUpdateTimes"
        const val LEGACY_KEY_LAST_UPDATE = "unifiedLastUpdate"
        private val LAST_UPDATE = longPreferencesKey("filters_last_update")
    }
}
