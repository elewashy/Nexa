package com.elewashy.nexa.feature.downloads.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadsDaoTest {

    private lateinit var db: NexaDatabase
    private lateinit var dao: DownloadsDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun download(id: Long, status: Int = DownloadStatusCodes.PENDING, createdAt: Long = id) =
        DownloadEntity(
            id = id, url = "https://example.com/$id", fileName = "f$id.bin",
            filePath = "/storage/f$id.bin", status = status, createdAt = createdAt,
        )

    @Test
    fun `upsert replaces and reads back`() = runTest {
        dao.upsert(download(1))
        dao.upsert(download(1).copy(status = DownloadStatusCodes.COMPLETED, downloadedBytes = 10))

        assertEquals(1, dao.count())
        assertEquals(DownloadStatusCodes.COMPLETED, dao.byId(1)?.status)
        assertEquals(10L, dao.byId(1)?.downloadedBytes)
    }

    @Test
    fun `segments replace wholesale and read ordered`() = runTest {
        dao.upsert(download(1))
        dao.replaceSegments(
            1,
            listOf(
                DownloadSegmentEntity(1, 100, 199, 0, false),
                DownloadSegmentEntity(1, 0, 99, 99, true),
            )
        )

        val segments = dao.segmentsFor(1)
        assertEquals(listOf(0L, 100L), segments.map { it.startByte })

        // Replace drops old rows (split shrank the first range).
        dao.replaceSegments(1, listOf(DownloadSegmentEntity(1, 0, 49, 49, true)))
        assertEquals(1, dao.segmentsFor(1).size)
    }

    @Test
    fun `delete cascades segment rows`() = runTest {
        dao.upsert(download(1))
        dao.replaceSegments(1, listOf(DownloadSegmentEntity(1, 0, 99, 10, false)))

        dao.delete(1)

        assertEquals(0, dao.count())
        assertEquals(0, dao.segmentsFor(1).size)
    }

    @Test
    fun `prune selects completed ids beyond the keep window only`() = runTest {
        dao.upsert(download(1, DownloadStatusCodes.COMPLETED, createdAt = 1))
        dao.upsert(download(2, DownloadStatusCodes.COMPLETED, createdAt = 2))
        dao.upsert(download(3, DownloadStatusCodes.COMPLETED, createdAt = 3))
        dao.upsert(download(4, DownloadStatusCodes.PAUSED, createdAt = 4))

        val pruned = dao.completedIdsBeyondKeep(keep = 2)
        assertEquals(listOf(1L), pruned)
        dao.deleteByIds(pruned)

        val remaining = dao.all().map { it.id }.toSet()
        assertEquals(setOf(2L, 3L, 4L), remaining)
    }

    @Test
    fun `prune edge cases never touch non-completed rows`() = runTest {
        dao.upsert(download(1, DownloadStatusCodes.COMPLETED, createdAt = 1))
        dao.upsert(download(2, DownloadStatusCodes.PAUSED, createdAt = 2))

        // keep covers all completed rows: nothing selected.
        assertEquals(emptyList<Long>(), dao.completedIdsBeyondKeep(keep = 5))

        // keep = 0: every completed row is selected, paused stays untouched.
        val pruned = dao.completedIdsBeyondKeep(keep = 0)
        assertEquals(listOf(1L), pruned)
        dao.deleteByIds(pruned)
        assertEquals(listOf(2L), dao.all().map { it.id })
    }

    @Test
    fun `meta stores the id sequence`() = runTest {
        assertEquals(null, dao.lastId())
        dao.setMeta(DownloadMetaEntity(lastId = 42))
        dao.setMeta(DownloadMetaEntity(lastId = 43))
        assertEquals(43L, dao.lastId())
    }
}
