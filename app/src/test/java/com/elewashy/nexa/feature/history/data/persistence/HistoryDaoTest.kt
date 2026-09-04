package com.elewashy.nexa.feature.history.data.persistence

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryDaoTest {

    private lateinit var db: NexaDatabase
    private lateinit var dao: HistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.historyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `each visit creates its own row`() = runTest {
        dao.insert(HistoryEntity(url = "https://a.example/", visitedAt = 100))
        dao.insert(HistoryEntity(url = "https://b.example/", visitedAt = 200))
        dao.insert(HistoryEntity(url = "https://a.example/", visitedAt = 300))

        assertEquals(3, dao.count())
        assertEquals(
            listOf(300L, 200L, 100L),
            dao.pagingNewest().loadFirstPage().map { it.visitedAt }
        )
    }

    @Test
    fun `title update applies to the identified visit only`() = runTest {
        dao.insert(HistoryEntity(url = "https://a.example/", title = "Old", visitedAt = 100))
        val latestId = dao.insert(HistoryEntity(url = "https://a.example/", visitedAt = 200))

        dao.updateTitle(latestId, "New")

        val rows = dao.pagingNewest().loadFirstPage()
        assertEquals("New", rows.first { it.visitedAt == 200L }.title)
        assertEquals("Old", rows.first { it.visitedAt == 100L }.title)
    }

    @Test
    fun `late title updates cannot attach to a newer visit with the same url`() = runTest {
        val oldId = dao.insert(
            HistoryEntity(url = "https://x.example/", title = "X old", visitedAt = 100)
        )
        dao.insert(HistoryEntity(url = "https://y.example/", title = "Y", visitedAt = 200))
        dao.insert(HistoryEntity(url = "https://x.example/", visitedAt = 300))

        dao.updateTitle(oldId, "X late")

        val rows = dao.pagingNewest().loadFirstPage()
        assertEquals("", rows.first { it.visitedAt == 300L }.title)
        assertEquals("X late", rows.first { it.visitedAt == 100L }.title)
        assertEquals("Y", rows.first { it.visitedAt == 200L }.title)
    }

    @Test
    fun `blank titles never overwrite`() = runTest {
        val id = dao.insert(HistoryEntity(url = "https://a.example/", title = "T", visitedAt = 1))
        dao.updateTitle(id, "")
        assertEquals("T", dao.pagingNewest().loadFirstPage().single().title)
    }

    @Test
    fun `paging source emits newest first`() = runTest {
        dao.insert(HistoryEntity(url = "https://old.example/", visitedAt = 1))
        dao.insert(HistoryEntity(url = "https://new.example/", visitedAt = 3))
        dao.insert(HistoryEntity(url = "https://mid.example/", visitedAt = 2))

        val page = dao.pagingNewest().loadFirstPage()
        assertEquals(
            listOf("https://new.example/", "https://mid.example/", "https://old.example/"),
            page.map { it.url }
        )
    }

    @Test
    fun `search matches title and url case-insensitively`() = runTest {
        dao.insert(HistoryEntity(url = "https://kotlin.example/", title = "Kotlin Docs", visitedAt = 1))
        dao.insert(HistoryEntity(url = "https://other.example/", title = "Unrelated", visitedAt = 2))

        val byTitle = dao.pagingSearch("%kotlin%").loadFirstPage()
        assertEquals(1, byTitle.size)
        assertEquals("https://kotlin.example/", byTitle.single().url)

        val byUrl = dao.pagingSearch("%OTHER%").loadFirstPage()
        assertEquals(1, byUrl.size)
        assertEquals("https://other.example/", byUrl.single().url)
    }

    @Test
    fun `delete removes a single visit`() = runTest {
        dao.insert(HistoryEntity(url = "https://a.example/", visitedAt = 1))
        dao.insert(HistoryEntity(url = "https://a.example/", visitedAt = 2))
        val older = dao.pagingNewest().loadFirstPage().first { it.visitedAt == 1L }

        dao.deleteById(older.id)

        assertEquals(listOf(2L), dao.pagingNewest().loadFirstPage().map { it.visitedAt })
    }

    @Test
    fun `clear empties the table`() = runTest {
        dao.insert(HistoryEntity(url = "https://a.example/", visitedAt = 1))
        dao.clear()
        assertEquals(0, dao.count())
    }

    @Test
    fun `prune drops visits older than the cutoff`() = runTest {
        dao.insert(HistoryEntity(url = "https://old.example/", visitedAt = 1))
        dao.insert(HistoryEntity(url = "https://new.example/", visitedAt = 500))

        dao.pruneOlderThan(cutoff = 100)

        assertEquals(listOf("https://new.example/"), dao.pagingNewest().loadFirstPage().map { it.url })
    }

    @Test
    fun `prune keeps only the most recent visits`() = runTest {
        repeat(5) { i ->
            dao.insert(HistoryEntity(url = "https://$i.example/", visitedAt = i.toLong()))
        }

        dao.pruneBeyond(keep = 2)

        val kept = dao.pagingNewest().loadFirstPage().map { it.url }
        assertEquals(listOf("https://4.example/", "https://3.example/"), kept)
    }

    @Test
    fun `age prune keeps the visit exactly at the cutoff`() = runTest {
        dao.insert(HistoryEntity(url = "https://edge.example/", visitedAt = 100))
        dao.insert(HistoryEntity(url = "https://older.example/", visitedAt = 99))

        val deleted = dao.pruneOlderThan(cutoff = 100)

        assertEquals(1, deleted)
        assertEquals(
            listOf("https://edge.example/"),
            dao.pagingNewest().loadFirstPage().map { it.url }
        )
    }

    @Test
    fun `count prune deletes nothing when keep covers the table`() = runTest {
        repeat(3) { i ->
            dao.insert(HistoryEntity(url = "https://$i.example/", visitedAt = i.toLong()))
        }

        assertEquals(0, dao.pruneBeyond(keep = 3))
        assertEquals(0, dao.pruneBeyond(keep = 10))
        assertEquals(3, dao.count())
    }

    @Test
    fun `prune on an empty table deletes nothing`() = runTest {
        assertEquals(0, dao.pruneOlderThan(cutoff = 100))
        assertEquals(0, dao.pruneBeyond(keep = 5))
    }

    private suspend fun PagingSource<Int, HistoryEntity>.loadFirstPage(): List<HistoryEntity> {
        val result = load(PagingSource.LoadParams.Refresh(null, 50, false))
        assertTrue("expected a page", result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page).data
    }
}
