package com.elewashy.nexa.feature.bookmarks.data.persistence

import android.content.Context
import app.cash.turbine.test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookmarksDaoTest {

    private lateinit var db: NexaDatabase
    private lateinit var dao: BookmarksDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.bookmarksDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bookmark(url: String, title: String = "", createdAt: Long = 100) =
        BookmarkEntity(url = url, title = title, createdAt = createdAt, updatedAt = createdAt)

    @Test
    fun `observeAll streams newest first`() = runTest {
        dao.insert(bookmark("https://a.example/", createdAt = 100))
        dao.insert(bookmark("https://b.example/", createdAt = 300))
        dao.insert(bookmark("https://c.example/", createdAt = 200))

        val rows = dao.observeAll().first()
        assertEquals(
            listOf("https://b.example/", "https://c.example/", "https://a.example/"),
            rows.map { it.url }
        )
    }

    @Test
    fun `duplicate url insert is ignored, never thrown`() = runTest {
        dao.insert(bookmark("https://a.example/"))
        // IGNORE, not ABORT: a racing toggle/undo for the same URL resolves
        // via the rowId instead of crashing on the unique index.
        val rowId = dao.insert(bookmark("https://a.example/"))
        assertEquals(-1L, rowId)
        assertEquals(1, dao.count())
    }

    @Test
    fun `observeSearch matches url and title`() = runTest {
        dao.insert(bookmark("https://kotlinlang.org/", title = "Kotlin"))
        dao.insert(bookmark("https://example.com/", title = "Docs about kotlin"))
        dao.insert(bookmark("https://other.net/", title = "Unrelated"))

        val rows = dao.observeSearch("%kotlin%").first()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.url != "https://other.net/" })
    }

    @Test
    fun `updateTitle bumps the title and updated_at only`() = runTest {
        val id = dao.insert(bookmark("https://a.example/", title = "Old", createdAt = 100))

        val changed = dao.updateTitle(id, "New", updatedAt = 555)

        assertEquals(1, changed)
        val row = dao.byId(id)
        assertNotNull(row)
        assertEquals("New", row!!.title)
        assertEquals(555L, row.updatedAt)
        assertEquals(100L, row.createdAt)
    }

    @Test
    fun `updateBookmark atomically edits fields and preserves position within a folder`() = runTest {
        val id = dao.insert(bookmark("https://old.example/", title = "Old", createdAt = 100))
        dao.updatePosition(id, 321L)

        val outcome = dao.updateBookmark(
            id = id,
            title = "New",
            url = "https://new.example/",
            folderId = null,
            movedPosition = 999L,
            updatedAt = 555L,
        )

        assertEquals(BookmarkUpdateOutcome.UPDATED, outcome)
        val row = dao.byId(id)!!
        assertEquals("New", row.title)
        assertEquals("https://new.example/", row.url)
        assertNull(row.folderId)
        assertEquals(321L, row.position)
        assertEquals(555L, row.updatedAt)
        assertEquals(100L, row.createdAt)
    }

    @Test
    fun `updateBookmark changes position and invalidates folder flows when location changes`() = runTest {
        val folderId = dao.insertFolder(
            BookmarkFolderEntity(
                title = "Destination",
                position = 1,
                createdAt = 1,
                updatedAt = 1,
            )
        )
        val id = dao.insert(bookmark("https://move.example/", title = "Move", createdAt = 100))

        dao.observeInFolder(folderId).test {
            assertTrue(awaitItem().isEmpty())
            assertEquals(
                BookmarkUpdateOutcome.UPDATED,
                dao.updateBookmark(
                    id = id,
                    title = "Moved",
                    url = "https://moved.example/",
                    folderId = folderId,
                    movedPosition = 777L,
                    updatedAt = 888L,
                ),
            )
            val moved = awaitItem().single()
            assertEquals(folderId, moved.folderId)
            assertEquals(777L, moved.position)
            assertEquals(888L, moved.updatedAt)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(dao.observeInFolder(null).first().isEmpty())
    }

    @Test
    fun `updateBookmark rejects another rows url without modifying either row`() = runTest {
        val firstId = dao.insert(bookmark("https://first.example/", title = "First", createdAt = 100))
        val secondId = dao.insert(bookmark("https://second.example/", title = "Second", createdAt = 200))

        val outcome = dao.updateBookmark(
            id = firstId,
            title = "Destructive replacement",
            url = "https://second.example/",
            folderId = null,
            movedPosition = 999L,
            updatedAt = 999L,
        )

        assertEquals(BookmarkUpdateOutcome.URL_CONFLICT, outcome)
        assertEquals("First", dao.byId(firstId)?.title)
        assertEquals("https://first.example/", dao.byId(firstId)?.url)
        assertEquals("Second", dao.byId(secondId)?.title)
        assertEquals(2, dao.count())
    }

    @Test
    fun `byUrl finds the row and deleteById removes it`() = runTest {
        val id = dao.insert(bookmark("https://a.example/"))

        assertEquals(id, dao.byUrl("https://a.example/")?.id)

        dao.deleteById(id)

        assertNull(dao.byUrl("https://a.example/"))
        assertEquals(0, dao.count())
    }
}
