package com.elewashy.nexa.feature.tabs.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.common.BrowserUrls
import com.elewashy.nexa.core.common.DispatcherProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.tabs.data.persistence.TabEntity
import com.elewashy.nexa.feature.tabs.data.persistence.TabsDao
import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Workspace semantics: restore self-healing, create/switch/close rules, and
 * the coalesced URL/title write policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TabRepositoryTest {

    private lateinit var db: NexaDatabase
    private lateinit var dao: TabsDao
    private lateinit var repository: TabRepositoryImpl

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
        override val mainImmediate = dispatcher
    }

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

    /** Builds the repository on the runTest scheduler (created inside tests). */
    private suspend fun TestScope.newRepository(): TabRepositoryImpl {
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        return TabRepositoryImpl(dao, backgroundScope, TestDispatchers(dispatcher))
    }

    @Test
    fun `restore seeds a home tab when the workspace is empty`() = runTest {
        repository = newRepository()

        repository.restore()

        assertTrue(repository.workspace.value.isRestored)
        assertEquals(1, repository.workspace.value.tabs.size)
        assertEquals(BrowserUrls.HOME, repository.workspace.value.tabs.single().url)
        assertEquals(repository.workspace.value.tabs.single().id, repository.workspace.value.activeTabId)
        assertTrue(repository.workspace.value.tabs.single().isActive)
    }

    @Test
    fun `restore is idempotent`() = runTest {
        repository = newRepository()

        repository.restore()
        val first = repository.workspace.value.tabs.single().id
        repository.restore()

        assertEquals(first, repository.workspace.value.tabs.single().id)
        assertEquals(1, dao.count())
    }

    @Test
    fun `restore heals a missing active pointer to the most recently used tab`() = runTest {
        // Crash mid-switch can leave zero active rows: seed two, both inactive.
        dao.insert(TabEntity(url = "https://old.example/", position = 0, isActive = false, createdAt = 1, lastAccessedAt = 10))
        val mru = dao.insert(TabEntity(url = "https://mru.example/", position = 1, isActive = false, createdAt = 2, lastAccessedAt = 99))
        repository = newRepository()

        repository.restore()

        assertEquals(mru, repository.workspace.value.activeTabId)
        assertEquals(2, repository.workspace.value.tabs.size)
    }

    @Test
    fun `restore rewrites unsafe urls to home but keeps the tab`() = runTest {
        dao.insert(TabEntity(url = "javascript:alert(1)", position = 0, isActive = true, createdAt = 1, lastAccessedAt = 1))
        repository = newRepository()

        repository.restore()

        assertEquals(1, repository.workspace.value.tabs.size)
        assertEquals(BrowserUrls.HOME, repository.workspace.value.tabs.single().url)
        assertEquals(BrowserUrls.HOME, dao.byPosition().single().url)
    }

    @Test
    fun `restore heals multiple active rows to the most recently used one`() = runTest {
        // Corrupted persistent state: two rows both flagged active.
        dao.insert(TabEntity(url = "https://a.example/", position = 0, isActive = true, createdAt = 1, lastAccessedAt = 10))
        val mruActive = dao.insert(TabEntity(url = "https://b.example/", position = 1, isActive = true, createdAt = 2, lastAccessedAt = 99))
        dao.insert(TabEntity(url = "https://c.example/", position = 2, isActive = false, createdAt = 3, lastAccessedAt = 50))
        repository = newRepository()

        repository.restore()

        assertEquals(mruActive, repository.workspace.value.activeTabId)
        val rows = dao.byPosition()
        assertEquals(1, rows.count { it.isActive })
        assertEquals(mruActive, rows.single { it.isActive }.id)
    }

    @Test
    fun `explicit flush between commits never regresses to an older url`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!

        // URL A -> explicit flush -> URL B -> window flush. Final state must be B.
        repository.urlCommitted(id, "https://a.example/")
        repository.flushPending()
        repository.urlCommitted(id, "https://b.example/")
        advanceUntilIdle()

        awaitRoom { dao.byPosition().single().url == "https://b.example/" }
    }

    @Test
    fun `newTab appends at the end and activates`() = runTest {
        repository = newRepository()
        repository.restore()
        val homeId = repository.workspace.value.activeTabId

        val newId = repository.newTab("https://a.example/")

        assertNotNull(newId)
        val tabs = repository.workspace.value.tabs
        assertEquals(2, tabs.size)
        assertEquals(listOf(homeId, newId), tabs.map { it.id })
        assertEquals(listOf(0, 1), tabs.map { it.position })
        assertEquals(newId, repository.workspace.value.activeTabId)
    }

    @Test
    fun `newTab sanitizes unsafe urls to home`() = runTest {
        repository = newRepository()
        repository.restore()

        val id = repository.newTab("file:///etc/passwd")

        val tab = repository.workspace.value.tabs.first { it.id == id }
        assertEquals(BrowserUrls.HOME, tab.url)
    }

    @Test
    fun `newTab returns null at the cap`() = runTest {
        repeat(TabRepository.MAX_TABS) { index ->
            dao.insert(
                TabEntity(
                    url = "https://t$index.example/",
                    position = index,
                    isActive = index == 0,
                    createdAt = index.toLong(),
                    lastAccessedAt = index.toLong(),
                )
            )
        }
        repository = newRepository()
        repository.restore()

        assertNull(repository.newTab("https://one-too-many.example/"))
        assertEquals(TabRepository.MAX_TABS, dao.count())
    }

    @Test
    fun `switchTo activates and touches the target tab`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!

        repository.switchTo(first)

        assertEquals(first, repository.workspace.value.activeTabId)
        val firstRow = dao.byPosition().first { it.id == first }
        val secondRow = dao.byPosition().first { it.id == second }
        assertTrue(firstRow.isActive)
        assertFalse(secondRow.isActive)
        assertTrue(firstRow.lastAccessedAt >= secondRow.lastAccessedAt)
    }

    @Test
    fun `switchTo an unknown id is a no-op`() = runTest {
        repository = newRepository()
        repository.restore()
        val active = repository.workspace.value.activeTabId

        repository.switchTo(9999L)

        assertEquals(active, repository.workspace.value.activeTabId)
    }

    @Test
    fun `pinning is durable canonical and survives repository restore`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!

        repository.pinTab(third)
        repository.pinTab(second)

        assertEquals(listOf(third, second, first), repository.workspace.value.tabs.map { it.id })
        assertEquals(listOf(true, true, false), repository.workspace.value.tabs.map { it.isPinned })
        assertEquals(listOf(0, 1, 2), dao.byPosition().map { it.position })

        repository = newRepository()
        repository.restore()

        assertEquals(listOf(third, second, first), repository.workspace.value.tabs.map { it.id })
        assertEquals(listOf(true, true, false), repository.workspace.value.tabs.map { it.isPinned })
    }

    @Test
    fun `unpinning moves a tab to the pinned boundary`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!
        repository.pinTab(first)
        repository.pinTab(second)
        repository.pinTab(third)

        repository.unpinTab(second)

        assertEquals(listOf(first, third, second), repository.workspace.value.tabs.map { it.id })
        assertEquals(listOf(true, true, false), repository.workspace.value.tabs.map { it.isPinned })
        assertEquals(listOf(0, 1, 2), repository.workspace.value.tabs.map { it.position })
    }

    @Test
    fun `reorder stays within the current pin segment`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!
        val fourth = repository.newTab("https://c.example/")!!
        repository.pinTab(first)
        repository.pinTab(second)

        repository.reorderTab(second, 0)
        repository.reorderTab(fourth, 2)
        assertEquals(listOf(second, first, fourth, third), repository.workspace.value.tabs.map { it.id })

        repository.reorderTab(third, 0)

        assertEquals(listOf(second, first, fourth, third), repository.workspace.value.tabs.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), dao.byPosition().map { it.position })
    }

    @Test
    fun `closing a pinned tab preserves canonical normalized order`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!
        repository.pinTab(first)
        repository.pinTab(second)

        repository.closeTab(first)

        assertEquals(listOf(second, third), repository.workspace.value.tabs.map { it.id })
        assertEquals(listOf(true, false), repository.workspace.value.tabs.map { it.isPinned })
        assertEquals(listOf(0, 1), dao.byPosition().map { it.position })
    }

    @Test
    fun `closing an inactive tab keeps the active tab`() = runTest {
        repository = newRepository()
        repository.restore()
        val active = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        repository.switchTo(active)

        repository.closeTab(second)

        assertEquals(1, repository.workspace.value.tabs.size)
        assertEquals(active, repository.workspace.value.activeTabId)
    }

    @Test
    fun `closing the active tab activates the next position`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!
        repository.switchTo(second)

        repository.closeTab(second)

        assertEquals(third, repository.workspace.value.activeTabId)
        assertEquals(listOf(first, third), repository.workspace.value.tabs.map { it.id })
    }

    @Test
    fun `closing the active last-position tab falls back to the previous tab`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!

        repository.closeTab(second)

        assertEquals(first, repository.workspace.value.activeTabId)
        assertEquals(1, repository.workspace.value.tabs.size)
    }

    @Test
    fun `closing the last tab creates a fresh home tab`() = runTest {
        repository = newRepository()
        repository.restore()
        val only = repository.workspace.value.activeTabId!!

        repository.closeTab(only)

        val tabs = repository.workspace.value.tabs
        assertEquals(1, tabs.size)
        assertEquals(BrowserUrls.HOME, tabs.single().url)
        assertEquals(tabs.single().id, repository.workspace.value.activeTabId)
        assertTrue(tabs.single().id != only)
    }

    @Test
    fun `closing selected tabs publishes one valid ordered workspace`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!
        repository.switchTo(first)

        repository.closeTabs(setOf(first, second))

        val workspace = repository.workspace.value
        assertEquals(listOf(third), workspace.tabs.map { it.id })
        assertEquals(third, workspace.activeTabId)
        assertTrue(workspace.tabs.single().isActive)
        assertEquals(listOf(0), dao.byPosition().map { it.position })
    }

    @Test
    fun `pinning selected tabs preserves their relative order`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        val third = repository.newTab("https://b.example/")!!

        repository.setTabsPinned(setOf(first, third), isPinned = true)

        val tabs = repository.workspace.value.tabs
        assertEquals(listOf(first, third, second), tabs.map { it.id })
        assertEquals(listOf(true, true, false), tabs.map { it.isPinned })
        assertEquals(listOf(0, 1, 2), tabs.map { it.position })
        assertEquals(listOf(first, third, second), dao.byPosition().map { it.id })
    }

    /**
     * Room suspend DAOs dispatch writes to Room's own executor thread, so a
     * flush submitted by the (virtual-time) test scheduler completes in real
     * time. Poll briefly instead of assuming synchronous visibility.
     */
    private suspend fun awaitRoom(timeoutMs: Long = 3000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Room state did not converge", condition())
    }

    @Test
    fun `url and title commits update flows immediately and eventually reach room`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!

        repository.urlCommitted(id, "https://committed.example/")
        repository.titleReceived(id, "Committed")

        // In-memory state is immediate. Do not assert Room is still stale here:
        // awaiting a Room query suspends this test and lets runTest advance the
        // background coalescing timer, so that assertion is scheduler-dependent.
        assertEquals("https://committed.example/", repository.workspace.value.tabs.single().url)
        assertEquals("Committed", repository.workspace.value.tabs.single().title)

        advanceUntilIdle()

        awaitRoom {
            val row = dao.byPosition().single()
            row.url == "https://committed.example/" && row.title == "Committed"
        }
    }

    @Test
    fun `repeated commits coalesce into the latest values`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!

        repository.urlCommitted(id, "https://one.example/")
        repository.urlCommitted(id, "https://two.example/")
        repository.urlCommitted(id, "https://three.example/")

        advanceUntilIdle()

        awaitRoom { dao.byPosition().single().url == "https://three.example/" }
    }

    @Test
    fun `flushPending persists without waiting for the window`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!

        repository.urlCommitted(id, "https://flushed.example/")
        repository.flushPending()

        awaitRoom { dao.byPosition().single().url == "https://flushed.example/" }
    }

    @Test
    fun `flushPending with an in-flight coalesce job loses no writes`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!

        // First commit schedules the coalesce job; second lands while that
        // job is still alive. The explicit flush (onStop path) cancels the
        // job and must persist BOTH writes' latest values, not drop them.
        repository.urlCommitted(id, "https://first.example/")
        repository.titleReceived(id, "First")
        repository.urlCommitted(id, "https://second.example/")
        repository.titleReceived(id, "Second")
        repository.flushPending()

        awaitRoom {
            val row = dao.byPosition().single()
            row.url == "https://second.example/" && row.title == "Second"
        }

        // Nothing may regress after the cancelled job's would-be window.
        advanceUntilIdle()
        val row = dao.byPosition().single()
        assertEquals("https://second.example/", row.url)
        assertEquals("Second", row.title)
    }

    @Test
    fun `private tabs are active in memory but never written to room`() = runTest {
        repository = newRepository()
        repository.restore()
        val normalId = repository.workspace.value.activeTabId!!

        val privateId = repository.newTab("https://private.example/", BrowsingMode.Private)!!
        repository.urlCommitted(privateId, "https://private.example/secret")
        repository.titleReceived(privateId, "Secret")
        repository.flushPending()

        assertTrue(privateId < 0)
        assertEquals(privateId, repository.workspace.value.activeTabId)
        assertTrue(repository.workspace.value.tabs.single { it.id == privateId }.isPrivate)
        assertEquals("Secret", repository.workspace.value.tabs.single { it.id == privateId }.title)
        assertEquals(listOf(normalId), dao.byPosition().map { it.id })
    }

    @Test
    fun `private pinning and reordering stay in memory and obey the boundary`() = runTest {
        repository = newRepository()
        repository.restore()
        val normalId = repository.workspace.value.activeTabId!!
        val first = repository.newTab("https://one.example/", BrowsingMode.Private)!!
        val second = repository.newTab("https://two.example/", BrowsingMode.Private)!!
        val third = repository.newTab("https://three.example/", BrowsingMode.Private)!!

        repository.pinTab(second)
        repository.reorderTab(third, 1)
        repository.reorderTab(first, 0)

        val privateTabs = repository.workspace.value.tabs.filter { it.isPrivate }
        assertEquals(listOf(second, third, first), privateTabs.map { it.id })
        assertEquals(listOf(true, false, false), privateTabs.map { it.isPinned })
        assertEquals(listOf(0, 1, 2), privateTabs.map { it.position })
        assertEquals(listOf(normalId), dao.byPosition().map { it.id })
    }

    @Test
    fun `closing active reordered private tab activates its canonical next tab`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.newTab("https://one.example/", BrowsingMode.Private)!!
        val second = repository.newTab("https://two.example/", BrowsingMode.Private)!!
        val third = repository.newTab("https://three.example/", BrowsingMode.Private)!!
        repository.pinTab(second)
        repository.reorderTab(third, 1)
        repository.switchTo(third)

        repository.closeTab(third)

        assertEquals(first, repository.workspace.value.activeTabId)
        assertEquals(listOf(second, first), repository.workspace.value.tabs.filter { it.isPrivate }.map { it.id })
        assertEquals(listOf(0, 1), repository.workspace.value.tabs.filter { it.isPrivate }.map { it.position })
    }

    @Test
    fun `closing the last private tab returns to the persisted normal tab`() = runTest {
        repository = newRepository()
        repository.restore()
        val normalId = repository.workspace.value.activeTabId!!
        val privateId = repository.newTab(BrowserUrls.HOME, BrowsingMode.Private)!!

        repository.closeTab(privateId)

        assertEquals(normalId, repository.workspace.value.activeTabId)
        assertTrue(repository.workspace.value.tabs.none { it.isPrivate })
        assertEquals(1, dao.count())
    }

    @Test
    fun `close all private tabs leaves normal workspace untouched`() = runTest {
        repository = newRepository()
        repository.restore()
        val normalId = repository.workspace.value.activeTabId!!
        repository.newTab("https://one.example/", BrowsingMode.Private)
        repository.newTab("https://two.example/", BrowsingMode.Private)

        repository.closeTabs(BrowsingMode.Private)

        assertEquals(normalId, repository.workspace.value.activeTabId)
        assertEquals(listOf(normalId), repository.workspace.value.tabs.map { it.id })
        assertEquals(1, dao.count())
    }

    @Test
    fun `closing a tab discards its pending writes`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!

        repository.urlCommitted(second, "https://never-persisted.example/")
        repository.closeTab(second)
        advanceUntilIdle()

        awaitRoom { dao.count() == 1 }
        assertEquals(first, dao.byPosition().single().id)
    }

    // ── Opener relation (Back at the first page of a popup tab) ─────────

    @Test
    fun `popup tab records a live same-mode opener only`() = runTest {
        repository = newRepository()
        repository.restore()
        val opener = repository.workspace.value.activeTabId!!

        val child = repository.newTab("https://child.example/", openerTabId = opener)!!
        val unknownOpener = repository.newTab("https://x.example/", openerTabId = 9_999L)!!
        val crossMode = repository.newTab("https://p.example/", BrowsingMode.Private, openerTabId = opener)!!

        val tabs = repository.workspace.value.tabs.associateBy { it.id }
        assertEquals(opener, tabs.getValue(child).openerTabId)
        assertNull(tabs.getValue(unknownOpener).openerTabId)
        assertNull(tabs.getValue(crossMode).openerTabId)
        assertEquals(opener, dao.byPosition().single { it.id == child }.openerTabId)
    }

    @Test
    fun `closing an active popup returns to its opener and preserves it`() = runTest {
        repository = newRepository()
        repository.restore()
        val opener = repository.workspace.value.activeTabId!!
        repository.urlCommitted(opener, "https://opener.example/page")
        repository.saveSessionState(opener, byteArrayOf(1, 2, 3))
        val unrelated = repository.newTab("https://unrelated.example/")!!
        repository.switchTo(opener)
        val child = repository.newTab("https://child.example/", openerTabId = opener)!!

        repository.closeTab(child)

        val workspace = repository.workspace.value
        // Without the opener rule the next position (unrelated) would win; the opener must.
        assertEquals(opener, workspace.activeTabId)
        assertEquals(listOf(opener, unrelated), workspace.tabs.map { it.id })
        assertEquals("https://opener.example/page", workspace.activeTab!!.url)
        assertArrayEquals(byteArrayOf(1, 2, 3), repository.sessionState(opener))
    }

    @Test
    fun `closing an opener re-parents its children to the nearest surviving ancestor`() = runTest {
        repository = newRepository()
        repository.restore()
        val root = repository.workspace.value.activeTabId!!
        val middle = repository.newTab("https://middle.example/", openerTabId = root)!!
        val leaf = repository.newTab("https://leaf.example/", openerTabId = middle)!!

        repository.closeTab(middle)
        assertEquals(root, repository.workspace.value.tabs.single { it.id == leaf }.openerTabId)
        assertEquals(root, dao.byPosition().single { it.id == leaf }.openerTabId)

        // Back from the leaf's first page now returns to the root.
        repository.closeTab(leaf)
        assertEquals(root, repository.workspace.value.activeTabId)
    }

    @Test
    fun `private popup returns to its private opener`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.newTab("https://one.example/", BrowsingMode.Private)!!
        repository.newTab("https://two.example/", BrowsingMode.Private)
        repository.switchTo(first)
        val child = repository.newTab("https://child.example/", BrowsingMode.Private, openerTabId = first)!!

        repository.closeTab(child)

        assertEquals(first, repository.workspace.value.activeTabId)
    }

    @Test
    fun `opener relation survives a restart`() = runTest {
        repository = newRepository()
        repository.restore()
        val opener = repository.workspace.value.activeTabId!!
        val child = repository.newTab("https://child.example/", openerTabId = opener)!!

        repository = newRepository()
        repository.restore()

        assertEquals(child, repository.workspace.value.activeTabId)
        assertEquals(opener, repository.workspace.value.openerOf(child)?.id)
    }

    // ── Navigation (session) state ───────────────────────────────────────

    @Test
    fun `session state is readable immediately and persisted by a flush`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!

        repository.saveSessionState(id, byteArrayOf(1))
        repository.saveSessionState(id, byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(2, 3), repository.sessionState(id))

        repository.flushPending()
        assertArrayEquals(byteArrayOf(2, 3), dao.sessionState(id))

        // A new process restores the exact navigation state from Room.
        repository = newRepository()
        repository.restore()
        assertArrayEquals(byteArrayOf(2, 3), repository.sessionState(id))
    }

    @Test
    fun `oversized session state clears the stored one`() = runTest {
        repository = newRepository()
        repository.restore()
        val id = repository.workspace.value.activeTabId!!
        repository.saveSessionState(id, byteArrayOf(1))
        repository.flushPending()

        repository.saveSessionState(id, ByteArray(TabRepository.MAX_SESSION_STATE_BYTES + 1))
        assertNull(repository.sessionState(id))
        repository.flushPending()

        assertNull(dao.sessionState(id))
    }

    @Test
    fun `session state of an unknown or closed tab is never stored`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://a.example/")!!
        repository.saveSessionState(second, byteArrayOf(5))
        repository.flushPending()

        repository.closeTab(second)
        repository.saveSessionState(second, byteArrayOf(6))
        repository.saveSessionState(9_999L, byteArrayOf(7))
        repository.flushPending()

        assertNull(repository.sessionState(second))
        assertNull(dao.sessionState(second))
        assertNull(dao.sessionState(9_999L))
        assertEquals(listOf(first), dao.byPosition().map { it.id })
    }

    @Test
    fun `private session state stays in memory and ends with the private session`() = runTest {
        repository = newRepository()
        repository.restore()
        val privateId = repository.newTab("https://p.example/", BrowsingMode.Private)!!

        repository.saveSessionState(privateId, byteArrayOf(4))
        repository.flushPending()
        assertArrayEquals(byteArrayOf(4), repository.sessionState(privateId))

        repository.closeTabs(BrowsingMode.Private)
        assertNull(repository.sessionState(privateId))
    }

    @Test
    fun `restore drops navigation state of tabs whose url is unsafe`() = runTest {
        val bad = dao.insert(TabEntity(url = "javascript:alert(1)", position = 0, isActive = true, createdAt = 1, lastAccessedAt = 1))
        dao.upsertSessionState(bad, byteArrayOf(1))
        repository = newRepository()

        repository.restore()

        assertNull(repository.sessionState(bad))
    }

    // ── Undo close ───────────────────────────────────────────────────────

    @Test
    fun `reopening a closed tab restores position pin opener title and history`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://second.example/", openerTabId = first)!!
        repository.newTab("https://third.example/")
        repository.titleReceived(second, "Second")
        repository.saveSessionState(second, byteArrayOf(8, 9))
        val closed = repository.workspace.value.tabs.single { it.id == second }

        repository.closeTab(second)
        val reopened = repository.reopenClosedTab(closed)!!

        val workspace = repository.workspace.value
        val tab = workspace.tabs.single { it.id == reopened }
        assertEquals(reopened, workspace.activeTabId)
        assertEquals(1, tab.position)
        assertEquals("Second", tab.title)
        assertEquals("https://second.example/", tab.url)
        assertEquals(first, tab.openerTabId)
        assertEquals(listOf(0, 1, 2), dao.byPosition().map { it.position })
        assertArrayEquals(byteArrayOf(8, 9), repository.sessionState(reopened))
        assertArrayEquals(byteArrayOf(8, 9), dao.sessionState(reopened))
    }

    @Test
    fun `reopening a pinned tab keeps it inside the pinned segment`() = runTest {
        repository = newRepository()
        repository.restore()
        val first = repository.workspace.value.activeTabId!!
        val second = repository.newTab("https://second.example/")!!
        repository.pinTab(second)
        val closed = repository.workspace.value.tabs.single { it.id == second }

        repository.closeTab(second)
        val reopened = repository.reopenClosedTab(closed)!!

        assertEquals(listOf(reopened, first), repository.workspace.value.tabs.map { it.id })
        assertEquals(listOf(true, false), repository.workspace.value.tabs.map { it.isPinned })
    }
}
