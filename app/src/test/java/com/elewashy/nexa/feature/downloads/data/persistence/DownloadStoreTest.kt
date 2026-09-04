package com.elewashy.nexa.feature.downloads.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadStoreTest {

    private lateinit var db: NexaDatabase
    private lateinit var store: DownloadStore
    private val captureCount = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DownloadStore(db, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(id: Long, status: DownloadStatus = DownloadStatus.PAUSED) = DownloadItem(
        id = id, url = "https://example.com/$id", fileName = "f$id.bin",
        filePath = "/storage/f$id.bin", status = status, createdAt = id,
    )

    /** Provider serving a fixed item; counts captures to prove coalescing. */
    private fun installProvider(item: DownloadItem, downloaded: Long = 5) {
        store.snapshotProvider = { ids ->
            captureCount.incrementAndGet()
            ids.filter { it == item.id }.map {
                DownloadSnapshot(
                    item.copy().toEntity().copy(downloadedBytes = downloaded),
                    listOf(DownloadSegmentEntity(it, 0, 99, downloaded, false)),
                )
            }
        }
    }

    @Test
    fun `repeated progress marks coalesce into one capture per drain`() = runTest {
        val item = item(1)
        installProvider(item)

        repeat(10) { store.markProgress(1) }
        store.drainDirtyNow()

        assertEquals(1, captureCount.get())
        assertEquals(5L, db.downloadsDao().byId(1)?.downloadedBytes)
        assertEquals(1, db.downloadsDao().segmentsFor(1).size)

        // Nothing dirty — a drain without marks captures nothing.
        store.drainDirtyNow()
        assertEquals(1, captureCount.get())
    }

    @Test
    fun `upsertNow writes structural state immediately`() = runTest {
        val item = item(2, DownloadStatus.FAILED)
        installProvider(item)

        store.upsertNow(listOf(2L))

        assertEquals(DownloadStatusCodes.FAILED, db.downloadsDao().byId(2)?.status)
    }

    @Test
    fun `structural write failure is observable and remains retryable`() = runTest {
        val item = item(20, DownloadStatus.FAILED)
        store.snapshotProvider = { throw android.database.sqlite.SQLiteFullException("disk full") }

        assertThrows(android.database.sqlite.SQLiteFullException::class.java) {
            kotlinx.coroutines.runBlocking { store.upsertNow(listOf(item.id)) }
        }
        assertNotNull(store.lastWriteFailure.value)

        // A later drain contains the failure and keeps the writer pipeline
        // alive instead of throwing from its long-running scope.
        store.drainDirtyNow()
        assertNotNull(store.lastWriteFailure.value)
    }

    @Test
    fun `later successful retry clears persistence failure`() = runTest {
        val item = item(21, DownloadStatus.FAILED)
        store.snapshotProvider = { throw android.database.sqlite.SQLiteFullException("disk full") }
        store.markProgress(item.id)
        store.drainDirtyNow()
        assertNotNull(store.lastWriteFailure.value)

        installProvider(item)
        store.drainDirtyNow()

        assertNull(store.lastWriteFailure.value)
        assertEquals(5L, db.downloadsDao().byId(item.id)?.downloadedBytes)
    }

    @Test
    fun `success for another id does not hide pending failure`() = runTest {
        val failed = item(22)
        val healthy = item(23)
        store.snapshotProvider = { ids ->
            ids.mapNotNull { id ->
                when (id) {
                    failed.id -> null
                    healthy.id -> DownloadSnapshot(healthy.toEntity(), emptyList())
                    else -> null
                }
            }
        }
        store.markProgress(failed.id)
        store.markProgress(healthy.id)

        store.drainDirtyNow()

        assertNotNull(store.lastWriteFailure.value)
        assertEquals(healthy.id, db.downloadsDao().byId(healthy.id)?.id)

        // The omitted id remained pending and recovers once capture is healthy.
        installProvider(failed)
        store.drainDirtyNow()
        assertNull(store.lastWriteFailure.value)
        assertEquals(failed.id, db.downloadsDao().byId(failed.id)?.id)
    }

    @Test
    fun `cancellation during snapshot capture requeues every drained id`() = runTest {
        val first = item(24)
        val second = item(25)
        store.markProgress(first.id)
        store.markProgress(second.id)
        store.snapshotProvider = { throw CancellationException("stop") }

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { store.drainDirtyNow() }
        }

        store.snapshotProvider = { ids ->
            ids.map { id -> DownloadSnapshot(item(id).toEntity(), emptyList()) }
        }
        store.drainDirtyNow()
        assertEquals(first.id, db.downloadsDao().byId(first.id)?.id)
        assertEquals(second.id, db.downloadsDao().byId(second.id)?.id)
    }

    @Test
    fun `successful delete supersedes a failed upsert and clears failure`() = runTest {
        val id = 26L
        store.snapshotProvider = { throw android.database.sqlite.SQLiteFullException("disk full") }
        store.markProgress(id)
        store.drainDirtyNow()
        assertNotNull(store.lastWriteFailure.value)

        store.delete(id)

        assertNull(store.lastWriteFailure.value)
        assertEquals(0, db.downloadsDao().count())
    }

    @Test
    fun `persistence retry delay backs off and resets after a healthy drain`() {
        assertEquals(1_000L, DownloadStore.nextDrainDelay(500L, failed = true))
        assertEquals(2_000L, DownloadStore.nextDrainDelay(1_000L, failed = true))
        assertEquals(30_000L, DownloadStore.nextDrainDelay(30_000L, failed = true))
        assertEquals(500L, DownloadStore.nextDrainDelay(30_000L, failed = false))
    }

    @Test
    fun `delete purges dirty mark and rows`() = runTest {
        val item = item(3)
        installProvider(item)
        store.upsertNow(listOf(3L))
        store.markProgress(3)

        store.delete(3L)
        store.drainDirtyNow()

        assertEquals(0, db.downloadsDao().count())
        assertEquals(1, captureCount.get()) // no resurrection write after delete
    }

    @Test
    fun `late progress batch cannot regress a newer structural status`() = runTest {
        val item = item(4, DownloadStatus.FAILED)
        installProvider(item)

        // Progress dirtied first, structural transition persisted second.
        store.markProgress(4)
        store.upsertNow(listOf(4L))
        // The late batch wakes afterwards; it re-captures CURRENT state, so
        // the structural status must survive it.
        store.drainDirtyNow()

        assertEquals(DownloadStatusCodes.FAILED, db.downloadsDao().byId(4)?.status)
    }

    @Test
    fun `postSnapshots writes without blocking and purges the dirty mark`() {
        val item = item(5)
        installProvider(item)
        store.markProgress(5)

        store.postSnapshots(
            listOf(DownloadSnapshot(item.toEntity().copy(downloadedBytes = 7), emptyList()))
        )
        // The write runs on the real writer thread; poll in real time.
        val deadline = System.currentTimeMillis() + 3000
        var landed = 0L
        while (System.currentTimeMillis() < deadline) {
            landed = kotlinx.coroutines.runBlocking { db.downloadsDao().byId(5)?.downloadedBytes ?: 0L }
            if (landed == 7L) break
            Thread.sleep(20)
        }
        assertEquals(7L, landed)

        // Dirty mark was purged — a drain captures nothing new.
        kotlinx.coroutines.runBlocking { store.drainDirtyNow() }
        assertEquals(0, captureCount.get())
    }

    @Test
    fun `legacy import persists valid records and seeds the id sequence`() = runTest {
        val state = LegacyDownloadStateReader.read(
            """
            {
              "lastId": 9,
              "items": [
                {"id": 1, "url": "https://a.example/", "fileName": "a.bin",
                 "filePath": "/storage/a.bin", "status": "COMPLETED", "createdAt": 1},
                {"id": 2, "url": "https://b.example/", "fileName": "b.bin",
                 "filePath": "/storage/b.bin", "status": "DOWNLOADING", "createdAt": 2}
              ],
              "segments": {"2": [{"startByte": 0, "endByte": 99, "downloadedBytes": 30}]}
            }
            """.trimIndent()
        )!!

        val imported = store.importLegacy(state)

        assertEquals(2, imported)
        // Active statuses are coerced to PAUSED on import.
        assertEquals(DownloadStatusCodes.PAUSED, db.downloadsDao().byId(2)?.status)
        assertEquals(DownloadStatusCodes.COMPLETED, db.downloadsDao().byId(1)?.status)
        assertEquals(1, db.downloadsDao().segmentsFor(2).size)
        assertEquals(9L, db.downloadsDao().lastId())
    }

    @Test
    fun `legacy import coerces the full status matrix`() = runTest {
        val state = LegacyDownloadStateReader.read(
            """
            {
              "lastId": 4,
              "items": [
                {"id": 1, "url": "https://a.example/", "fileName": "a.bin",
                 "filePath": "/storage/a.bin", "status": "PENDING", "createdAt": 1},
                {"id": 2, "url": "https://b.example/", "fileName": "b.bin",
                 "filePath": "/storage/b.bin", "status": "FAILED", "createdAt": 2},
                {"id": 3, "url": "https://c.example/", "fileName": "c.bin",
                 "filePath": "/storage/c.bin", "status": "CANCELLED", "createdAt": 3},
                {"id": 4, "url": "https://d.example/", "fileName": "d.bin",
                 "filePath": "/storage/d.bin", "createdAt": 4}
              ],
              "segments": {}
            }
            """.trimIndent()
        )!!

        val imported = store.importLegacy(state)

        assertEquals(4, imported)
        // PENDING must not survive as an auto-starting status after upgrade.
        assertEquals(DownloadStatusCodes.PAUSED, db.downloadsDao().byId(1)?.status)
        assertEquals(DownloadStatusCodes.FAILED, db.downloadsDao().byId(2)?.status)
        assertEquals(DownloadStatusCodes.CANCELLED, db.downloadsDao().byId(3)?.status)
        // Missing status (Gson null) degrades to PAUSED, never an active code.
        assertEquals(DownloadStatusCodes.PAUSED, db.downloadsDao().byId(4)?.status)
    }

    @Test
    fun `one malformed record never aborts the legacy import`() = runTest {
        val state = LegacyDownloadStateReader.read(
            """
            {
              "lastId": 2,
              "items": [
                {"id": 1, "url": "https://a.example/", "fileName": "a.bin",
                 "filePath": "/storage/a.bin", "status": "PAUSED", "createdAt": 1},
                {"id": 2, "url": "https://b.example/", "fileName": "b.bin",
                 "filePath": "/storage/b.bin", "status": "PAUSED", "createdAt": 2}
              ],
              "segments": {
                "1": [{"startByte": 5, "downloadedBytes": 2}],
                "2": [{"startByte": 0, "endByte": 99, "downloadedBytes": 30}]
              }
            }
            """.trimIndent()
        )!!

        val imported = store.importLegacy(state)

        // Both items import; the segment missing endByte is dropped, not fatal.
        assertEquals(2, imported)
        assertEquals(0, db.downloadsDao().segmentsFor(1).size)
        assertEquals(1, db.downloadsDao().segmentsFor(2).size)
    }
}
