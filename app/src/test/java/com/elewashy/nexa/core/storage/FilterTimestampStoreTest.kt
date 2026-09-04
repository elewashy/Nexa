package com.elewashy.nexa.core.storage

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 4 consolidation: the filters-last-updated timestamp moved from a
 * dedicated SharedPreferences file to the app DataStore. These tests pin the
 * one-time legacy migration semantics (idempotent, crash-safe, never
 * overwriting a newer authoritative value).
 */
@RunWith(RobolectricTestRunner::class)
class FilterTimestampStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: FilterTimestampStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = {
                File(context.filesDir, "filter_ts_test_${System.nanoTime()}.preferences_pb")
            },
        )
        store = FilterTimestampStore(
            context = context,
            dataStore = dataStore,
            appScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    @After
    fun tearDown() {
        runBlocking { dataStore.data.first() } // flush pending edits
    }

    private fun seedLegacy(value: Long) {
        context
            .getSharedPreferences(FilterTimestampStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(FilterTimestampStore.LEGACY_KEY_LAST_UPDATE, value) }
    }

    private fun legacyPresent(): Boolean =
        context
            .getSharedPreferences(FilterTimestampStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .contains(FilterTimestampStore.LEGACY_KEY_LAST_UPDATE)

    @Test
    fun `fresh install reads zero and save emits`() = runBlocking {
        store.migrateLegacy()
        assertEquals(0L, store.lastUpdate.first())

        store.save(123L)

        assertEquals(123L, store.lastUpdate.first())
        assertFalse(legacyPresent())
    }

    @Test
    fun `legacy value migrates to DataStore and retires`() = runBlocking {
        seedLegacy(555L)

        store.migrateLegacy()

        assertEquals(555L, store.lastUpdate.first())
        assertFalse(legacyPresent())
    }

    @Test
    fun `migration is idempotent across repeated runs`() = runBlocking {
        seedLegacy(555L)

        store.migrateLegacy()
        store.migrateLegacy()

        assertEquals(555L, store.lastUpdate.first())
        assertFalse(legacyPresent())
    }

    @Test
    fun `migration never overwrites a newer authoritative value`() = runBlocking {
        seedLegacy(555L)
        store.save(999L)

        store.migrateLegacy()

        // Kill-between-steps recovery: the newer DataStore value wins and the
        // legacy key is still retired.
        assertEquals(999L, store.lastUpdate.first())
        assertFalse(legacyPresent())
    }
}
