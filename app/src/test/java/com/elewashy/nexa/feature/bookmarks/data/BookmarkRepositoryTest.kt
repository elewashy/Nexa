package com.elewashy.nexa.feature.bookmarks.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarksDao
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkItem
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookmarkRepositoryTest {

    private lateinit var db: NexaDatabase
    private lateinit var dao: BookmarksDao
    private lateinit var repository: BookmarkRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.bookmarksDao()
        repository = BookmarkRepositoryImpl(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `toggle adds then removes the same url`() = runTest {
        assertTrue(repository.toggle("https://a.example/", "A"))
        assertEquals(1, dao.count())

        assertFalse(repository.toggle("https://a.example/", "A"))
        assertEquals(0, dao.count())
    }

    @Test
    fun `toggle ignores blank urls`() = runTest {
        assertFalse(repository.toggle("", "T"))
        assertFalse(repository.toggle("   ", "T"))
        assertEquals(0, dao.count())
    }

    @Test
    fun `observeBookmarks streams newest first and supports search`() = runTest {
        repository.toggle("https://kotlinlang.org/", "Kotlin")
        repository.toggle("https://example.com/", "Docs about kotlin")
        repository.toggle("https://other.net/", "Unrelated")

        val all = repository.observeBookmarks("").first()
        assertEquals(3, all.size)
        assertEquals("https://other.net/", all.first().url)

        val matches = repository.observeBookmarks("kotlin").first()
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.url != "https://other.net/" })
    }

    @Test
    fun `updateTitle renames an existing bookmark`() = runTest {
        repository.toggle("https://a.example/", "Old")
        val id = repository.byUrl("https://a.example/")!!.id

        repository.updateTitle(id, "New")

        assertEquals("New", repository.byUrl("https://a.example/")?.title)
    }

    @Test
    fun `updateBookmark trims and edits title url and folder atomically`() = runTest {
        val folderId = repository.createFolder("Destination", null)
        repository.toggle("https://old.example/", "Old")
        val before = repository.byUrl("https://old.example/")!!
        dao.updatePosition(before.id, 7L)

        val result = repository.updateBookmark(
            id = before.id,
            title = "  New title  ",
            url = "  https://new.example/  ",
            folderId = folderId,
        )

        assertEquals(BookmarkUpdateResult.UPDATED, result)
        assertNull(repository.byUrl("https://old.example/"))
        val updated = repository.byUrl("https://new.example/")!!
        assertEquals("New title", updated.title)
        assertEquals(folderId, updated.folderId)
        assertTrue(updated.position != 7L)
        assertTrue(updated.updatedAt >= before.updatedAt)
        assertEquals(before.createdAt, updated.createdAt)
    }

    @Test
    fun `updateBookmark rejects blank title or url without changing the row`() = runTest {
        repository.toggle("https://kept.example/", "Kept")
        val item = repository.byUrl("https://kept.example/")!!

        assertEquals(
            BookmarkUpdateResult.INVALID_INPUT,
            repository.updateBookmark(item.id, "   ", "https://changed.example/", null),
        )
        assertEquals(
            BookmarkUpdateResult.INVALID_INPUT,
            repository.updateBookmark(item.id, "Changed", "   ", null),
        )

        assertEquals(item, repository.byUrl(item.url))
        assertEquals(1, dao.count())
    }

    @Test
    fun `updateBookmark duplicate url leaves both bookmarks intact`() = runTest {
        repository.toggle("https://first.example/", "First")
        repository.toggle("https://second.example/", "Second")
        val first = repository.byUrl("https://first.example/")!!

        assertEquals(
            BookmarkUpdateResult.URL_ALREADY_EXISTS,
            repository.updateBookmark(first.id, "Replacement", "https://second.example/", null),
        )

        assertEquals("First", repository.byUrl("https://first.example/")?.title)
        assertEquals("Second", repository.byUrl("https://second.example/")?.title)
        assertEquals(2, dao.count())
    }

    @Test
    fun `updateBookmark preserves manual position without a folder move`() = runTest {
        repository.toggle("https://same-folder.example/", "Old")
        val item = repository.byUrl("https://same-folder.example/")!!
        dao.updatePosition(item.id, 42L)

        assertEquals(
            BookmarkUpdateResult.UPDATED,
            repository.updateBookmark(item.id, "New", "https://renamed.example/", null),
        )

        assertEquals(42L, repository.byUrl("https://renamed.example/")?.position)
    }

    @Test
    fun `all folders flow exposes nested edit destinations`() = runTest {
        val rootId = repository.createFolder("Zulu", null)
        repository.createFolder("Alpha", rootId)

        assertEquals(
            listOf("Alpha", "Zulu"),
            repository.observeAllFolders().first().map { it.title },
        )
    }

    @Test
    fun `delete and reinsert restore the same row identity`() = runTest {
        repository.toggle("https://a.example/", "A")
        val item = repository.byUrl("https://a.example/")!!

        repository.delete(item.id)
        assertNull(repository.byUrl("https://a.example/"))

        repository.reinsert(item)

        val restored = repository.byUrl("https://a.example/")
        assertNotNull(restored)
        assertEquals(item.id, restored!!.id)
        assertEquals(item.createdAt, restored.createdAt)
        assertEquals("A", restored.title)
    }

    @Test
    fun `byUrl is null for unknown or blank urls`() = runTest {
        assertNull(repository.byUrl("https://missing.example/"))
        assertNull(repository.byUrl(""))
    }

    @Test
    fun `bookmark state remains observable when the same url is opened later`() = runTest {
        val url = "https://persistent.example/page"
        repository.toggle(url, "Persistent")

        // A fresh observer represents reopening the page in a later browser
        // session; Room remains the source of truth rather than transient UI.
        assertTrue(repository.observeIsBookmarked(url).first())
        assertFalse(repository.observeIsBookmarked("https://other.example/").first())
    }

    @Test
    fun `toggle captures the title given at bookmark time`() = runTest {
        repository.toggle("https://a.example/", "Page title")
        assertEquals("Page title", repository.byUrl("https://a.example/")?.title)
    }

    @Test
    fun `folders persist and moving a bookmark updates folder contents`() = runTest {
        val folderId = repository.createFolder("Projects", parentId = null)
        repository.toggle("https://project.example/", "Project")
        val bookmark = repository.byUrl("https://project.example/")!!

        repository.moveBookmark(bookmark.id, folderId)

        assertTrue(repository.observeBookmarksInFolder(null).first().isEmpty())
        assertEquals(
            listOf("https://project.example/"),
            repository.observeBookmarksInFolder(folderId).first().map { it.url },
        )
        val folder = repository.observeFolders(null).first().single()
        assertEquals("Projects", folder.title)
        assertEquals(1, folder.itemCount)
    }

    @Test
    fun `folder counters include direct bookmarks and subfolders`() = runTest {
        val parentId = repository.createFolder("Parent", parentId = null)
        repository.createFolder("Child", parentId = parentId)
        repository.toggle("https://inside.example/", "Inside")
        repository.moveBookmark(repository.byUrl("https://inside.example/")!!.id, parentId)

        assertEquals(2, repository.observeFolders(null).first().single().itemCount)
    }

    @Test
    fun `moving folders rejects cycles and accepts valid destinations`() = runTest {
        val parentId = repository.createFolder("Parent", parentId = null)
        val childId = repository.createFolder("Child", parentId = parentId)

        repository.moveFolder(parentId, childId)
        assertNull(repository.folderById(parentId)?.parentId)

        repository.moveFolder(childId, null)
        assertNull(repository.folderById(childId)?.parentId)
    }

    @Test
    fun `manual folder movement swaps adjacent folders even when positions collide`() = runTest {
        repository.createFolder("First", parentId = null)
        repository.createFolder("Second", parentId = null)
        val before = repository.observeFolders(null).first()

        repository.swapFolderOrder(before[0], before[1])

        assertEquals(
            before.map { it.id }.reversed(),
            repository.observeFolders(null).first().map { it.id },
        )
    }

    @Test
    fun `bookmark drag reorder supports arbitrary indices and clamps stale targets`() = runTest {
        repeat(5) { index -> repository.toggle("https://$index.example/", "Item $index") }
        val before = repository.observeBookmarksInFolder(null).first()
        val movedId = before.first().id

        repository.moveBookmarkToIndex(movedId, folderId = null, targetIndex = 3)
        val movedDown = repository.observeBookmarksInFolder(null).first()
        assertEquals(movedId, movedDown[3].id)
        assertEquals(before.drop(1).take(3).map { it.id }, movedDown.take(3).map { it.id })

        repository.moveBookmarkToIndex(movedId, folderId = null, targetIndex = -100)
        assertEquals(movedId, repository.observeBookmarksInFolder(null).first().first().id)

        repository.moveBookmarkToIndex(movedId, folderId = null, targetIndex = Int.MAX_VALUE)
        assertEquals(movedId, repository.observeBookmarksInFolder(null).first().last().id)
    }

    @Test
    fun `mixed sibling reorder freely interleaves folders and bookmarks`() = runTest {
        val firstFolderId = repository.createFolder("First folder", parentId = null)
        val secondFolderId = repository.createFolder("Second folder", parentId = null)
        repository.toggle("https://middle.example/", "Middle website")
        val bookmarkId = repository.byUrl("https://middle.example/")!!.id

        // Establish Folder → Folder → Website, then drag the website between both folders.
        dao.updateFolderPosition(firstFolderId, 0L)
        dao.updateFolderPosition(secondFolderId, 1_024L)
        dao.updatePosition(bookmarkId, 2_048L)
        repository.moveSiblingToIndex(bookmarkId, isFolder = false, parentId = null, targetIndex = 1)

        val foldersById = repository.observeFolders(null).first().associateBy { it.id }
        val bookmarksById = repository.observeBookmarksInFolder(null).first().associateBy { it.id }
        val mixed = buildList {
            foldersById.values.forEach { add("folder:${it.id}" to it.position) }
            bookmarksById.values.forEach { add("bookmark:${it.id}" to it.position) }
        }.sortedBy { it.second }.map { it.first }

        assertEquals(
            listOf("folder:$firstFolderId", "bookmark:$bookmarkId", "folder:$secondFolderId"),
            mixed,
        )
    }

    @Test
    fun `bookmark reorder never crosses folder boundaries`() = runTest {
        val folderId = repository.createFolder("Folder", parentId = null)
        repeat(3) { index -> repository.toggle("https://root-$index.example/", "Root $index") }
        repository.toggle("https://nested.example/", "Nested")
        val nested = repository.byUrl("https://nested.example/")!!
        repository.moveBookmark(nested.id, folderId)
        val roots = repository.observeBookmarksInFolder(null).first()

        repository.moveBookmarkToIndex(roots.last().id, folderId = null, targetIndex = 0)

        assertEquals(nested.id, repository.observeBookmarksInFolder(folderId).first().single().id)
        assertEquals(roots.last().id, repository.observeBookmarksInFolder(null).first().first().id)
    }

    @Test
    fun `folder drag reorder only changes siblings`() = runTest {
        val parentId = repository.createFolder("Parent", parentId = null)
        repeat(3) { index -> repository.createFolder("Root $index", parentId = null) }
        val childId = repository.createFolder("Child", parentId = parentId)
        val roots = repository.observeFolders(null).first()
        val movedId = roots.first().id

        repository.moveFolderToIndex(movedId, parentId = null, targetIndex = roots.lastIndex)

        assertEquals(movedId, repository.observeFolders(null).first().last().id)
        assertEquals(childId, repository.observeFolders(parentId).first().single().id)
    }

    @Test
    fun `deleting a folder safely moves its contents to the parent`() = runTest {
        val parentId = repository.createFolder("Parent", parentId = null)
        val childId = repository.createFolder("Child", parentId = parentId)
        repository.toggle("https://kept.example/", "Kept")
        val bookmark = repository.byUrl("https://kept.example/")!!
        repository.moveBookmark(bookmark.id, childId)

        val snapshot = repository.deleteFolder(repository.folderById(childId)!!)

        assertEquals(parentId, repository.byUrl(bookmark.url)?.folderId)
        assertTrue(repository.observeFolders(parentId).first().isEmpty())

        repository.restoreDeletedFolders(listOf(snapshot))
        assertEquals(childId, repository.byUrl(bookmark.url)?.folderId)
        assertEquals("Child", repository.observeFolders(parentId).first().single().title)
    }

    @Test
    fun `concurrent toggles of the same url are linearizable`() = runTest {
        repeat(50) { iteration ->
            val url = "https://race-$iteration.example/"
            val start = CyclicBarrier(2)
            val results = List(2) {
                async(Dispatchers.IO) {
                    start.await()
                    repository.toggle(url, "T")
                }
            }.awaitAll()

            // Starting absent, two toggles must behave like add then remove,
            // regardless of which caller linearizes first.
            assertEquals(setOf(true, false), results.toSet())
            assertNull(repository.byUrl(url))
        }
        assertEquals(0, dao.count())
        assertTrue(repository.observeBookmarks("").first().isEmpty())
    }

    @Test
    fun `concurrent toggles of different urls do not interfere`() = runTest {
        val start = CyclicBarrier(2)
        val results = listOf("a", "b").map { key ->
            async(Dispatchers.IO) {
                start.await()
                repository.toggle("https://$key.example/", key)
            }
        }.awaitAll()

        assertEquals(listOf(true, true), results)
        assertEquals(2, dao.count())
    }

    @Test
    fun `delete racing toggle has a valid linearized outcome`() = runTest {
        repeat(25) { iteration ->
            val url = "https://delete-race-$iteration.example/"
            repository.toggle(url, "T")
            val existing = repository.byUrl(url)!!
            val start = CyclicBarrier(2)
            val delete = async(Dispatchers.IO) {
                start.await()
                repository.delete(existing.id)
            }
            val toggle = async(Dispatchers.IO) {
                start.await()
                repository.toggle(url, "T")
            }

            delete.await()
            val toggleAdded = toggle.await()
            assertEquals(toggleAdded, repository.byUrl(url) != null)
        }
    }

    @Test
    fun `title edit racing delete cannot resurrect the row`() = runTest {
        val url = "https://edit-delete-race.example/"
        repository.toggle(url, "Old")
        val existing = repository.byUrl(url)!!
        val start = CyclicBarrier(2)
        val edit = async(Dispatchers.IO) {
            start.await()
            repository.updateTitle(existing.id, "New")
        }
        val delete = async(Dispatchers.IO) {
            start.await()
            repository.delete(existing.id)
        }

        edit.await()
        delete.await()
        assertNull(repository.byUrl(url))
    }

    @Test
    fun `concurrent duplicate reinserts converge to one row`() = runTest {
        val item = BookmarkItem(
            id = 99,
            url = "https://undo-race.example/",
            title = "Undo",
            createdAt = 1,
            updatedAt = 1,
        )
        val start = CyclicBarrier(2)
        List(2) {
            async(Dispatchers.IO) {
                start.await()
                repository.reinsert(item)
            }
        }.awaitAll()

        assertEquals(1, dao.count())
        assertEquals(item.id, repository.byUrl(item.url)?.id)
    }

    @Test
    fun `reinsert no-ops when the url was re-bookmarked meanwhile`() = runTest {
        repository.toggle("https://a.example/", "Original")
        val item = repository.byUrl("https://a.example/")!!
        repository.delete(item.id)

        // The user re-bookmarks the URL while the undo snackbar is pending.
        repository.toggle("https://a.example/", "Fresh")

        // Undo must not crash on the unique index; the live bookmark wins.
        repository.reinsert(item)

        assertEquals(1, dao.count())
        assertEquals("Fresh", repository.byUrl("https://a.example/")?.title)
    }
}
