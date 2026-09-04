package com.elewashy.nexa.feature.downloads.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadStore
import com.elewashy.nexa.feature.downloads.data.persistence.LegacyDownloadStateReader
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
 * Crash boundary: the import committed but the process died before the legacy
 * file was retired. On next startup Room is authoritative, the stray file must
 * be archived, and records must not be duplicated.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadRepositoryLegacyRetirementTest {

    private val legacyJson = """
        {
          "lastId": 2,
          "items": [
            {"id": 1, "url": "https://a.example/", "fileName": "a.bin",
             "filePath": "/storage/a.bin", "status": "COMPLETED", "createdAt": 1},
            {"id": 2, "url": "https://b.example/", "fileName": "b.bin",
             "filePath": "/storage/b.bin", "status": "PAUSED", "createdAt": 2}
          ],
          "segments": {}
        }
    """.trimIndent()

    @Test
    fun `stray legacy file is retired without duplicate import`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = DownloadStore(db, Dispatchers.IO)

        // The import already committed in the previous (killed) process.
        val state = LegacyDownloadStateReader.read(legacyJson)!!
        runBlocking { store.importLegacy(state) }
        assertEquals(2, runBlocking { store.count() })

        // Kill left the legacy file behind.
        val legacy = File(context.filesDir, "download_state.json")
        legacy.writeText(legacyJson)

        // Next startup: init must archive the stray file, not re-import.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = DownloadRepositoryImpl(context, scope, store)
        repository.downloads // triggers async initialisation

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && legacy.exists()) {
            Thread.sleep(50)
        }

        assertFalse("legacy file must be archived", legacy.exists())
        assertTrue(
            "expected an .imported- archive",
            context.filesDir.listFiles().orEmpty().any {
                it.name.startsWith("download_state.json.imported-")
            }
        )
        assertEquals("no duplicate import", 2, runBlocking { store.count() })
    }
}
