package com.elewashy.nexa.feature.downloads.data.persistence

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [DownloadPersistence] via the internal test seam constructor
 * (plain directory instead of Context/filesDir).
 *
 * Covers:
 *  - write → load round-trip (status coercion, lastId, segments map)
 *  - corrupt-file quarantine (rename to `.corrupt-*`, empty result, no crash)
 *  - CRITICAL regression guard: writeToDisk must never overwrite non-empty
 *    persisted segments with an empty snapshot for DOWNLOADING/PAUSED items.
 */
class DownloadPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun stateDir(): File = tempFolder.newFolder()

    private fun item(
        id: Long,
        status: DownloadStatus,
        fileName: String = "file_$id.bin"
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        filePath = "/storage/$fileName",
        totalBytes = 100,
        downloadedBytes = 50,
        status = status
    )

    private fun seg(start: Long, end: Long, downloaded: Long, completed: Boolean = false) =
        PersistedSegment(startByte = start, endByte = end, downloadedBytes = downloaded, completed = completed)

    // ── write → load round-trip ─────────────────────────────────────────

    @Test
    fun `round trip restores items with coerced statuses`() {
        val dir = stateDir()
        DownloadPersistence(dir).apply {
            idCounter.set(42)
            forceFlush(
                items = listOf(
                    item(1, DownloadStatus.DOWNLOADING),
                    item(2, DownloadStatus.PENDING),
                    item(3, DownloadStatus.PAUSED),
                    item(4, DownloadStatus.COMPLETED),
                    item(5, DownloadStatus.FAILED)
                )
            )
        }

        val restored = DownloadPersistence(dir).load()

        assertEquals(5, restored.size)
        // Active statuses at crash time must not auto-start on restore.
        assertEquals(DownloadStatus.PAUSED, restored.single { it.id == 1L }.status)
        assertEquals(DownloadStatus.PAUSED, restored.single { it.id == 2L }.status)
        // Already-settled statuses are preserved as-is.
        assertEquals(DownloadStatus.PAUSED, restored.single { it.id == 3L }.status)
        assertEquals(DownloadStatus.COMPLETED, restored.single { it.id == 4L }.status)
        assertEquals(DownloadStatus.FAILED, restored.single { it.id == 5L }.status)
        // Payload fields survive the round-trip.
        assertEquals("file_1.bin", restored.single { it.id == 1L }.fileName)
        assertEquals(100L, restored.single { it.id == 1L }.totalBytes)
    }

    @Test
    fun `round trip restores lastId counter`() {
        val dir = stateDir()
        DownloadPersistence(dir).apply {
            idCounter.set(42)
            forceFlush(items = listOf(item(3, DownloadStatus.PAUSED)))
        }

        val persistence = DownloadPersistence(dir)
        persistence.load()
        assertEquals(42L, persistence.idCounter.get())
    }

    @Test
    fun `load bumps id counter past restored item ids`() {
        val dir = stateDir()
        DownloadPersistence(dir).apply {
            // lastId written BELOW an existing item id (defensive case).
            idCounter.set(5)
            forceFlush(items = listOf(item(3, DownloadStatus.PAUSED), item(9, DownloadStatus.COMPLETED)))
        }

        val persistence = DownloadPersistence(dir)
        persistence.load()
        assertEquals(9L, persistence.idCounter.get())
    }

    @Test
    fun `round trip restores segment snapshots`() {
        val dir = stateDir()
        val segments = mapOf(
            1L to listOf(seg(0, 49, 50, completed = true), seg(50, 99, 20)),
            2L to listOf(seg(0, 99, 0))
        )
        DownloadPersistence(dir).apply {
            idCounter.set(2)
            forceFlush(
                items = listOf(item(1, DownloadStatus.PAUSED), item(2, DownloadStatus.PAUSED)),
                segments = segments
            )
        }

        val persistence = DownloadPersistence(dir)
        persistence.load()
        assertEquals(segments[1L], persistence.restoredSegments(1))
        assertEquals(segments[2L], persistence.restoredSegments(2))
        assertEquals(emptyList<PersistedSegment>(), persistence.restoredSegments(999))
    }

    @Test
    fun `load on missing state file returns empty without crashing`() {
        val persistence = DownloadPersistence(stateDir())
        assertEquals(emptyList<DownloadItem>(), persistence.load())
    }

    @Test
    fun `flushIfDirty only writes when dirty`() {
        val dir = stateDir()
        val persistence = DownloadPersistence(dir)

        persistence.flushIfDirty(items = listOf(item(1, DownloadStatus.PAUSED)))
        assertFalse("no write expected without markDirty", File(dir, "download_state.json").exists())

        persistence.markDirty()
        persistence.flushIfDirty(items = listOf(item(1, DownloadStatus.PAUSED)))
        assertTrue(File(dir, "download_state.json").exists())
    }

    // ── corrupt-file quarantine ─────────────────────────────────────────

    @Test
    fun `corrupt state file is quarantined and load returns empty`() {
        val dir = stateDir()
        File(dir, "download_state.json").writeText("{this is not valid json!!")

        val persistence = DownloadPersistence(dir)
        // Must not throw.
        assertEquals(emptyList<DownloadItem>(), persistence.load())

        // Original file is gone; a quarantined copy is kept for inspection.
        assertFalse(File(dir, "download_state.json").exists())
        val quarantined = dir.listFiles { file -> file.name.startsWith("download_state.json.corrupt-") }
        assertTrue("expected a quarantined .corrupt-* file", !quarantined.isNullOrEmpty())
    }

    @Test
    fun `blank state file is treated as absent, not corrupt`() {
        val dir = stateDir()
        File(dir, "download_state.json").writeText("   ")

        val persistence = DownloadPersistence(dir)
        assertEquals(emptyList<DownloadItem>(), persistence.load())
        // Blank is not corruption — nothing is quarantined.
        assertTrue(File(dir, "download_state.json").exists())
        val quarantined = dir.listFiles { file -> file.name.startsWith("download_state.json.corrupt-") }
        assertTrue(quarantined.isNullOrEmpty())
    }

    // ── CRITICAL regression guard: never clobber resumable segments ────

    @Test
    fun `empty snapshot must not clobber persisted segments of downloading item`() {
        val dir = stateDir()
        val segments = mapOf(1L to listOf(seg(0, 49, 50, completed = true), seg(50, 99, 20)))

        val persistence = DownloadPersistence(dir)
        persistence.idCounter.set(1)
        persistence.forceFlush(items = listOf(item(1, DownloadStatus.DOWNLOADING)), segments = segments)

        // A restored-but-not-yet-resumed task reports an EMPTY in-memory
        // segment list; flushing that must not destroy the resumable state.
        persistence.forceFlush(items = listOf(item(1, DownloadStatus.DOWNLOADING)), segments = emptyMap())

        val reloaded = DownloadPersistence(dir)
        reloaded.load()
        assertEquals(segments[1L], reloaded.restoredSegments(1))
    }

    @Test
    fun `empty snapshot must not clobber persisted segments of paused item`() {
        val dir = stateDir()
        val segments = mapOf(7L to listOf(seg(0, 99, 30)))

        val persistence = DownloadPersistence(dir)
        persistence.idCounter.set(7)
        persistence.forceFlush(items = listOf(item(7, DownloadStatus.PAUSED)), segments = segments)
        persistence.forceFlush(items = listOf(item(7, DownloadStatus.PAUSED)), segments = emptyMap())

        val reloaded = DownloadPersistence(dir)
        reloaded.load()
        assertEquals(segments[7L], reloaded.restoredSegments(7))
    }

    @Test
    fun `guard still holds across a restart before resume`() {
        val dir = stateDir()
        val segments = mapOf(1L to listOf(seg(0, 99, 40)))

        DownloadPersistence(dir).apply {
            idCounter.set(1)
            forceFlush(items = listOf(item(1, DownloadStatus.DOWNLOADING)), segments = segments)
        }

        // Process restart: load() restores items (coerced to PAUSED) and the
        // last-good segments; the first flush of the new process arrives with
        // an empty in-memory snapshot and must fall back to the loaded state.
        val restarted = DownloadPersistence(dir)
        val restoredItems = restarted.load()
        restarted.forceFlush(items = restoredItems, segments = emptyMap())

        val reloaded = DownloadPersistence(dir)
        reloaded.load()
        assertEquals(segments[1L], reloaded.restoredSegments(1))
    }

    @Test
    fun `fresh non-empty snapshot replaces the persisted one`() {
        val dir = stateDir()
        val persistence = DownloadPersistence(dir)
        persistence.idCounter.set(1)
        persistence.forceFlush(
            items = listOf(item(1, DownloadStatus.DOWNLOADING)),
            segments = mapOf(1L to listOf(seg(0, 99, 10)))
        )

        val updated = listOf(seg(0, 99, 60))
        persistence.forceFlush(
            items = listOf(item(1, DownloadStatus.DOWNLOADING)),
            segments = mapOf(1L to updated)
        )

        val reloaded = DownloadPersistence(dir)
        reloaded.load()
        assertEquals(updated, reloaded.restoredSegments(1))
    }

    @Test
    fun `segments of removed items are dropped`() {
        val dir = stateDir()
        val persistence = DownloadPersistence(dir)
        persistence.idCounter.set(2)
        persistence.forceFlush(
            items = listOf(item(1, DownloadStatus.DOWNLOADING), item(2, DownloadStatus.PAUSED)),
            segments = mapOf(1L to listOf(seg(0, 99, 10)), 2L to listOf(seg(0, 99, 20)))
        )

        // Item 2 is deleted between flushes — its segments must not linger.
        persistence.forceFlush(
            items = listOf(item(1, DownloadStatus.DOWNLOADING)),
            segments = mapOf(1L to listOf(seg(0, 99, 10)), 2L to listOf(seg(0, 99, 20)))
        )

        val reloaded = DownloadPersistence(dir)
        reloaded.load()
        assertEquals(listOf(seg(0, 99, 10)), reloaded.restoredSegments(1))
        assertEquals(emptyList<PersistedSegment>(), reloaded.restoredSegments(2))
    }

    @Test
    fun `guard does not resurrect segments for completed items`() {
        // Resume state only matters for DOWNLOADING/PAUSED items; completed
        // ones may legitimately lose their segment snapshots.
        val dir = stateDir()
        val persistence = DownloadPersistence(dir)
        persistence.idCounter.set(1)
        persistence.forceFlush(
            items = listOf(item(1, DownloadStatus.COMPLETED)),
            segments = mapOf(1L to listOf(seg(0, 99, 100, completed = true)))
        )
        persistence.forceFlush(items = listOf(item(1, DownloadStatus.COMPLETED)), segments = emptyMap())

        val reloaded = DownloadPersistence(dir)
        reloaded.load()
        assertEquals(emptyList<PersistedSegment>(), reloaded.restoredSegments(1))
    }
}
