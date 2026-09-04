package com.elewashy.nexa.feature.tabs.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TabsDaoTest {

    private lateinit var db: NexaDatabase
    private lateinit var dao: TabsDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.tabsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tab(
        url: String = "https://example.com/",
        position: Int = 0,
        isActive: Boolean = false,
        isPinned: Boolean = false,
        createdAt: Long = 100,
        lastAccessedAt: Long = 100,
    ) = TabEntity(
        url = url,
        title = "",
        position = position,
        isPinned = isPinned,
        isActive = isActive,
        createdAt = createdAt,
        lastAccessedAt = lastAccessedAt,
    )

    @Test
    fun `byPosition returns rows in deterministic order`() = runTest {
        val first = dao.insert(tab(url = "https://a.example/", position = 0))
        val second = dao.insert(tab(url = "https://b.example/", position = 1))

        val rows = dao.byPosition()
        assertEquals(listOf(first, second), rows.map { it.id })
    }

    @Test
    fun `insertAndActivate moves the active pointer atomically`() = runTest {
        val first = dao.insertAndActivate(tab(position = 0))
        assertEquals(first, dao.activeTab()?.id)

        val second = dao.insertAndActivate(tab(position = 1))
        assertEquals(second, dao.activeTab()?.id)
        // Exactly one active row after the second activation.
        assertEquals(1, dao.byPosition().count { it.isActive })
    }

    @Test
    fun `activate clears every other active flag`() = runTest {
        val a = dao.insertAndActivate(tab(position = 0))
        val b = dao.insert(tab(position = 1))

        dao.activate(b)

        assertEquals(b, dao.activeTab()?.id)
        assertNull(dao.byIdActive(a))
    }

    @Test
    fun `deleteAndActivate removes the row and moves the pointer`() = runTest {
        val a = dao.insertAndActivate(tab(position = 0))
        val b = dao.insert(tab(position = 1))

        dao.deleteActivateAndReorder(a, nextId = b, orderedIds = listOf(b))

        assertEquals(1, dao.count())
        assertEquals(b, dao.activeTab()?.id)
    }

    @Test
    fun `deleteAndActivate with null next leaves no active row`() = runTest {
        val a = dao.insertAndActivate(tab(position = 0))

        dao.deleteActivateAndReorder(a, nextId = null, orderedIds = emptyList())

        assertEquals(0, dao.count())
        assertNull(dao.activeTab())
    }

    @Test
    fun `updateUrl updateTitle and touch target one row`() = runTest {
        val a = dao.insert(tab(url = "https://a.example/", position = 0))
        val b = dao.insert(tab(url = "https://b.example/", position = 1))

        dao.updateUrl(a, "https://changed.example/")
        dao.updateTitle(a, "Changed")
        dao.touch(a, 999)

        val rowA = dao.byPosition().first { it.id == a }
        val rowB = dao.byPosition().first { it.id == b }
        assertEquals("https://changed.example/", rowA.url)
        assertEquals("Changed", rowA.title)
        assertEquals(999L, rowA.lastAccessedAt)
        assertEquals("https://b.example/", rowB.url)
    }

    @Test
    fun `setPinnedAndOrder persists pin state and normalized order atomically`() = runTest {
        val a = dao.insert(tab(url = "https://a.example/", position = 0))
        val b = dao.insert(tab(url = "https://b.example/", position = 1))
        val c = dao.insert(tab(url = "https://c.example/", position = 2))

        dao.setPinnedAndOrder(c, isPinned = true, orderedIds = listOf(c, a, b))

        val rows = dao.byPosition()
        assertEquals(listOf(c, a, b), rows.map { it.id })
        assertEquals(listOf(true, false, false), rows.map { it.isPinned })
        assertEquals(listOf(0, 1, 2), rows.map { it.position })
    }

    @Test
    fun `deleteActivateAndReorder closes position gaps`() = runTest {
        val a = dao.insertAndActivate(tab(position = 0))
        val b = dao.insert(tab(position = 1))
        val c = dao.insert(tab(position = 2))

        dao.deleteActivateAndReorder(b, nextId = null, orderedIds = listOf(a, c))

        assertEquals(listOf(a, c), dao.byPosition().map { it.id })
        assertEquals(listOf(0, 1), dao.byPosition().map { it.position })
    }
}

private suspend fun TabsDao.byIdActive(id: Long): TabEntity? =
    byPosition().firstOrNull { it.id == id && it.isActive }
