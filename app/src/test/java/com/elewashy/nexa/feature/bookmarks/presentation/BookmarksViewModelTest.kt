package com.elewashy.nexa.feature.bookmarks.presentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.bookmarks.data.BookmarkRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookmarksViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: NexaDatabase
    private lateinit var repository: BookmarkRepositoryImpl
    private lateinit var viewModel: BookmarksViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BookmarkRepositoryImpl(db.bookmarksDao())
        viewModel = BookmarksViewModel(repository)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `confirmEdit updates all bookmark fields and closes edit after success`() = runTest(dispatcher) {
        val folderId = repository.createFolder("Destination", null)
        repository.toggle("https://old.example/", "Old")
        val item = repository.byUrl("https://old.example/")!!
        viewModel.startEdit(item)

        viewModel.confirmEdit("  New title  ", "  https://new.example/  ", folderId)
        viewModel.editingItem.filter { it == null }.first()

        val updated = repository.byUrl("https://new.example/")
        assertNotNull(updated)
        assertEquals("New title", updated?.title)
        assertEquals(folderId, updated?.folderId)
        assertNull(viewModel.editError.value)
    }

    @Test
    fun `confirmEdit keeps edit open and exposes duplicate url error`() = runTest(dispatcher) {
        repository.toggle("https://first.example/", "First")
        repository.toggle("https://second.example/", "Second")
        val first = repository.byUrl("https://first.example/")!!
        viewModel.startEdit(first)

        viewModel.confirmEdit("Changed", "https://second.example/", null)
        val error = viewModel.editError.filterNotNull().first()

        assertEquals(BookmarkEditError.URL_ALREADY_EXISTS, error)
        assertEquals(first.id, viewModel.editingItem.value?.id)
        assertEquals("First", repository.byUrl(first.url)?.title)
        assertEquals("Second", repository.byUrl("https://second.example/")?.title)
    }

    @Test
    fun `consecutive deletes are merged into one undo snapshot`() = runTest(dispatcher) {
        repository.toggle("https://first.example/", "First")
        repository.toggle("https://second.example/", "Second")
        val first = repository.byUrl("https://first.example/")!!
        val second = repository.byUrl("https://second.example/")!!

        viewModel.delete(first)
        viewModel.delete(second)
        viewModel.undoState.filter { it.count == 2 }.first()

        viewModel.undo()
        repository.observeBookmarks("").filter { it.size == 2 }.first()

        assertNotNull(repository.byUrl(first.url))
        assertNotNull(repository.byUrl(second.url))
    }

    @Test
    fun `folder rename remains separate and all folders are edit destinations`() = runTest(dispatcher) {
        val rootId = repository.createFolder("Root", null)
        val childId = repository.createFolder("Child", rootId)
        val child = repository.folderById(childId)!!

        viewModel.destinationFolders.test {
            var destinations = awaitItem()
            while (destinations.size != 2) destinations = awaitItem()
            assertEquals(setOf(rootId, childId), destinations.mapTo(mutableSetOf()) { it.id })
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.startEditFolder(child)
        viewModel.confirmFolderRename("Renamed")
        viewModel.editingFolder.filter { it == null }.first()

        assertEquals("Renamed", repository.folderById(childId)?.title)
    }
}
