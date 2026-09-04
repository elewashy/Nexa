package com.elewashy.nexa.feature.history.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.history.domain.model.HistoryItem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {

    private lateinit var db: NexaDatabase
    private lateinit var repository: HistoryRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HistoryRepositoryImpl(db.historyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `non-http schemes are not recorded`() = runTest {
        repository.recordVisit("file:///etc/hosts", isReload = false)
        repository.recordVisit("about:blank", isReload = false)
        repository.recordVisit(null, isReload = false)
        repository.recordVisit("https://real.example/", isReload = false)

        assertEquals(1, db.historyDao().count())
    }

    @Test
    fun `revisits create separate visits, reloads do not`() = runTest {
        repository.recordVisit("https://a.example/", isReload = false)
        repository.recordVisit("https://a.example/", isReload = false)
        repository.recordVisit("https://a.example/", isReload = true)

        assertEquals(2, db.historyDao().count())
    }

    @Test
    fun `updateTitle targets the exact visit and delete removes one`() = runTest {
        val visitId = repository.recordVisit("https://a.example/", isReload = false)!!
        repository.updateTitle(visitId, "Title A")

        assertEquals("Title A", db.historyDao().byId(1)?.title)
        repository.delete(1L)
        assertEquals(0, db.historyDao().count())
    }

    @Test
    fun `reinsert restores a deleted visit`() = runTest {
        val visitId = repository.recordVisit("https://a.example/", isReload = false)!!
        repository.updateTitle(visitId, "Title A")
        val entity = db.historyDao().byId(1)!!
        val item = HistoryItem(
            id = entity.id, url = entity.url, title = entity.title, visitedAt = entity.visitedAt,
        )

        repository.delete(item.id)
        assertEquals(0, db.historyDao().count())
        repository.reinsert(listOf(item))
        assertEquals(1, db.historyDao().count())
        assertEquals("Title A", db.historyDao().byId(1)?.title)
    }

    @Test
    fun `bulk delete returns only selected rows and can be undone`() = runTest {
        val first = repository.recordVisit("https://a.example/", isReload = false)!!
        val second = repository.recordVisit("https://b.example/", isReload = false)!!
        val kept = repository.recordVisit("https://kept.example/", isReload = false)!!

        assertEquals(setOf(first, second, kept), repository.matchingIds(""))
        assertEquals(setOf(kept), repository.matchingIds("kept"))

        val removed = repository.delete(setOf(first, second))
        assertEquals(2, removed.size)
        assertEquals(1, db.historyDao().count())

        repository.reinsert(removed)
        assertEquals(3, db.historyDao().count())
    }

    @Test
    fun `omnibox local matches are deduplicated`() = runTest {
        val first = repository.recordVisit("https://frequent.example/docs", isReload = false)!!
        repository.updateTitle(first, "Frequent docs")
        repository.recordVisit("https://other.example/", isReload = false)
        repository.recordVisit("https://frequent.example/docs", isReload = false)

        val frequent = repository.frequentSuggestions(limit = 8)
        val matches = repository.searchSuggestions(query = "docs", limit = 8)

        assertEquals("https://frequent.example/docs", frequent.first().url)
        assertEquals(2, frequent.first().visitCount)
        assertEquals(listOf("https://frequent.example/docs"), matches.map { it.url })
        assertEquals(2, matches.single().visitCount)
    }

    @Test
    fun `clearAll returns every row for undo`() = runTest {
        repository.recordVisit("https://a.example/", isReload = false)
        repository.recordVisit("https://b.example/", isReload = false)

        val removed = repository.clearAll()
        assertEquals(0, db.historyDao().count())
        assertEquals(2, removed.size)

        repository.reinsert(removed)
        assertEquals(2, db.historyDao().count())
    }
}
