package com.elewashy.nexa.feature.downloads.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * First-time legacy import (the one-shot upgrade path from v1.2.2 and older):
 * validation, completed-cap, id-sequence clamp, artifact retirement, the
 * pre-1.2.0 SharedPreferences array format, corrupt-file containment, and the
 * empty-segment clobber guard for restored-but-unresumed downloads.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadRepositoryLegacyImportTest {

    private fun newRepository(
        context: Context = ApplicationProvider.getApplicationContext(),
    ): Pair<DownloadRepositoryImpl, DownloadStore> {
        val db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = DownloadStore(db, Dispatchers.IO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return DownloadRepositoryImpl(context, scope, store) to store
    }

    private fun awaitInit(store: DownloadStore, expectedRows: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (runBlocking { store.count() } == expectedRows) return
            Thread.sleep(50)
        }
        assertEquals(expectedRows, runBlocking { store.count() })
    }

    @Test
    fun `fresh install records completed legacy decision even with no rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "download_state.json").delete()
        context.getSharedPreferences("DownloadPrefs", Context.MODE_PRIVATE).edit().clear().commit()

        val (repository, store) = newRepository(context)
        repository.downloads

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !runBlocking { store.legacyImported() }) {
            Thread.sleep(50)
        }
        assertTrue(runBlocking { store.legacyImported() })
        assertEquals(0, runBlocking { store.count() })
    }

    @Test
    fun `first-time import validates, caps, seeds ids and retires the file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = File(context.filesDir, "download_state.json")

        // 3 valid records + 1 invalid (blank fileName) + 120 completed (cap 100).
        val completedItems = (10..129).joinToString(",") { id ->
            """{"id": $id, "url": "https://c$id.example/", "fileName": "c$id.bin",
                "filePath": "/storage/c$id.bin", "status": "COMPLETED", "createdAt": $id}"""
        }
        legacy.writeText(
            """
            {
              "lastId": 4,
              "items": [
                {"id": 1, "url": "https://a.example/", "fileName": "a.bin",
                 "filePath": "/storage/a.bin", "status": "PAUSED", "createdAt": 1},
                {"id": 2, "url": "https://b.example/", "fileName": "",
                 "filePath": "/storage/b.bin", "status": "PAUSED", "createdAt": 2},
                {"id": 3, "url": "https://d.example/", "fileName": "d.bin",
                 "filePath": "/storage/d.bin", "status": "DOWNLOADING", "createdAt": 3},
                $completedItems
              ],
              "segments": {
                "1": [{"startByte": 0, "endByte": 999, "downloadedBytes": 500, "completed": false}]
              }
            }
            """.trimIndent()
        )

        val (repository, store) = newRepository(context)
        repository.downloads

        // 2 valid active/paused + 100 capped completed = 102 rows.
        awaitInit(store, expectedRows = 102)

        val db = runBlocking { store.loadAll() }
        // Invalid record skipped, active statuses coerced to PAUSED.
        assertFalse("blank-fileName record must be skipped", db.any { it.id == 2L })
        assertEquals(
            com.elewashy.nexa.feature.downloads.data.persistence.DownloadStatusCodes.PAUSED,
            db.first { it.id == 3L }.status
        )
        // Segments imported only for resumable items.
        assertEquals(1, runBlocking { store.segmentsFor(1L) }.size)
        // Id sequence clamped to max(item id) when the document lies below it.
        assertEquals(129L, runBlocking { store.lastId() })
        // Artifact retired, never deleted outright.
        assertFalse(legacy.exists())
        assertTrue(
            context.filesDir.listFiles().orEmpty().any {
                it.name.startsWith("download_state.json.imported-")
            }
        )
    }

    @Test
    fun `pre-1 2 0 SharedPreferences array format is imported`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The released pre-1.2.0 backend stored a bare JSON array of items in
        // SharedPreferences plus a separate last_download_id key.
        context.getSharedPreferences("DownloadPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "download_items",
                """[
                    {"id": 5, "url": "https://old.example/", "fileName": "old.bin",
                     "filePath": "/storage/old.bin", "status": "COMPLETED", "createdAt": 9}
                ]"""
            )
            .putLong("last_download_id", 7)
            .commit()

        val (repository, store) = newRepository(context)
        repository.downloads

        awaitInit(store, expectedRows = 1)
        val rows = runBlocking { store.loadAll() }
        assertEquals(5L, rows.single().id)
        assertEquals("old.bin", rows.single().fileName)
        // Id sequence comes from the separate prefs key.
        assertEquals(7L, runBlocking { store.lastId() })
        // Prefs cleared exactly once the import lands.
        val prefs = context.getSharedPreferences("DownloadPrefs", Context.MODE_PRIVATE)
        assertEquals(0, prefs.all.size)
    }

    @Test
    fun `corrupt legacy file is quarantined and initialisation still completes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = File(context.filesDir, "download_state.json")
        legacy.writeText("{not json")

        val (repository, store) = newRepository(context)
        repository.downloads

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && legacy.exists()) {
            Thread.sleep(50)
        }

        assertFalse("corrupt file must be quarantined", legacy.exists())
        assertTrue(
            context.filesDir.listFiles().orEmpty().any {
                it.name.startsWith("download_state.json.corrupt-")
            }
        )
        assertEquals(0, runBlocking { store.count() })
        assertTrue(runBlocking { store.legacyImported() })
    }

    @Test
    fun `blank legacy file is retired with no rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = File(context.filesDir, "download_state.json")
        legacy.writeText("   ")

        val (repository, store) = newRepository(context)
        repository.downloads

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && legacy.exists()) {
            Thread.sleep(50)
        }

        assertFalse(legacy.exists())
        assertEquals(0, runBlocking { store.count() })
        assertTrue(runBlocking { store.legacyImported() })
    }

    @Test
    fun `restored download keeps its segments when flushed before resume`() {
        // The deleted DownloadPersistence's critical regression guard, re-pinned
        // for the Room backend: a restored-but-not-yet-resumed task has no live
        // segments; a flush must fall back to the persisted rows instead of
        // wiping them (which would restart the download from byte 0).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = DownloadStore(db, Dispatchers.IO)
        val dao = db.downloadsDao()

        runBlocking {
            dao.upsert(
                com.elewashy.nexa.feature.downloads.data.persistence.DownloadEntity(
                    id = 1,
                    url = "https://a.example/",
                    fileName = "a.bin",
                    filePath = "/storage/a.bin",
                    totalBytes = 1000,
                    downloadedBytes = 500,
                    status = com.elewashy.nexa.feature.downloads.data.persistence.DownloadStatusCodes.PAUSED,
                    source = "UNKNOWN",
                    createdAt = 1,
                )
            )
            dao.insertSegments(
                listOf(
                    com.elewashy.nexa.feature.downloads.data.persistence.DownloadSegmentEntity(
                        downloadId = 1, startByte = 0, endByte = 499,
                        downloadedBytes = 499, completed = true
                    ),
                    com.elewashy.nexa.feature.downloads.data.persistence.DownloadSegmentEntity(
                        downloadId = 1, startByte = 500, endByte = 999,
                        downloadedBytes = 1, completed = false
                    ),
                )
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = DownloadRepositoryImpl(context, scope, store)
        repository.downloads
        awaitInit(store, expectedRows = 1)

        // Structural flush of the never-resumed task (e.g. pause/status write).
        runBlocking { store.upsertNow(listOf(1L)) }

        val segments = runBlocking { dao.segmentsFor(1L) }
        assertEquals("resume state must survive an unresumed flush", 2, segments.size)
        assertEquals(499L, segments[0].downloadedBytes)
        assertEquals(1L, segments[1].downloadedBytes)
    }
}
