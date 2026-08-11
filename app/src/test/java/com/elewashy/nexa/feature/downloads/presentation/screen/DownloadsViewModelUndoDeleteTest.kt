package com.elewashy.nexa.feature.downloads.presentation.screen

import app.cash.turbine.test
import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.domain.usecase.CancelDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.DismissNotificationsWarningUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ObserveDownloadsUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ObserveNotificationsWarningUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.PauseDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.ResumeDownloadUseCase
import com.elewashy.nexa.feature.downloads.domain.usecase.RetryDownloadUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the undoable-delete flow in [DownloadsViewModel]:
 * requestDelete hides items immediately and queues a snackbar, UNDO restores
 * them without touching the repository, and CONFIRM commits through
 * `cancel()`.
 *
 * Uses kotlinx-coroutines-test (one shared [StandardTestDispatcher]: installed
 * as Main for the viewModelScope AND passed to `runTest` so both sides share a
 * scheduler) and Turbine to observe `uiState`. The repository is a fake built
 * on a `MutableStateFlow` so the VM's collectors see realistic emissions.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadsViewModelUndoDeleteTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Fake repository ─────────────────────────────────────────────────

    private class FakeDownloadRepository(
        initial: List<DownloadItem> = emptyList()
    ) : DownloadRepository {

        private val _downloads = MutableStateFlow(initial)
        override val downloads: StateFlow<List<DownloadItem>> = _downloads

        private val _warning = MutableStateFlow<String?>(null)
        override val notificationsWarning: StateFlow<String?> = _warning

        /** Ids that `cancel()` was invoked with, in call order. */
        val cancelled = mutableListOf<Long>()

        override fun dismissNotificationsWarning() {
            _warning.value = null
        }

        override fun handleForegroundTimeout() = Unit
        override fun stopServiceIfIdle() = Unit
        override fun attachService(service: android.app.Service) = Unit
        override fun detachService() = Unit
        override suspend fun start(request: DownloadRequest) = Unit
        override suspend fun pause(id: Long) = Unit
        override suspend fun resume(id: Long) = Unit

        override suspend fun cancel(id: Long) {
            cancelled.add(id)
            // Mirror the real repository: a cancelled download disappears
            // from the snapshot stream.
            _downloads.value = _downloads.value.filterNot { it.id == id }
        }

        override suspend fun remove(id: Long) {
            _downloads.value = _downloads.value.filterNot { it.id == id }
        }

        override suspend fun retry(id: Long) = Unit

        override fun observe(id: Long): Flow<DownloadItem?> = flow {
            emit(_downloads.value.firstOrNull { it.id == id })
        }
    }

    private fun item(id: Long, fileName: String = "file_$id.mp4") = DownloadItem(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        filePath = "/storage/$fileName",
        status = DownloadStatus.COMPLETED
    )

    private fun viewModel(
        repository: FakeDownloadRepository,
        appScope: CoroutineScope
    ) = DownloadsViewModel(
        observeDownloads = ObserveDownloadsUseCase(repository),
        observeNotificationsWarning = ObserveNotificationsWarningUseCase(repository),
        dismissNotificationsWarningUseCase = DismissNotificationsWarningUseCase(repository),
        pauseDownload = PauseDownloadUseCase(repository),
        resumeDownload = ResumeDownloadUseCase(repository),
        cancelDownload = CancelDownloadUseCase(repository),
        retryDownload = RetryDownloadUseCase(repository),
        applicationScope = appScope
    )

    // ── requestDelete hides + queues ────────────────────────────────────

    @Test
    fun `requestDelete hides the item and queues one snackbar`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(listOf(1L, 2L), awaitItem().downloads.map { it.id })

            vm.requestDelete(item(1))

            val state = awaitItem()
            assertEquals(listOf(2L), state.downloads.map { it.id })
            assertEquals(1, state.deleteSnackbarQueue.size)
            val snackbar = state.deleteSnackbarQueue.single()
            assertEquals(1L, snackbar.token)
            assertEquals("file_1.mp4", snackbar.fileName)
            assertEquals(1, snackbar.itemCount)

            cancelAndIgnoreRemainingEvents()
        }
        // Nothing is deleted until the snackbar resolves.
        assertTrue(repo.cancelled.isEmpty())
    }

    @Test
    fun `undo restores the item and does not cancel`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(listOf(1L, 2L), awaitItem().downloads.map { it.id })

            vm.requestDelete(item(1))
            val hidden = awaitItem()
            val token = hidden.deleteSnackbarQueue.single().token

            vm.onDeleteSnackbarResult(token, undo = true)

            val restored = awaitItem()
            assertEquals(listOf(1L, 2L), restored.downloads.map { it.id })
            assertTrue(restored.deleteSnackbarQueue.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("undo must not call cancel()", repo.cancelled.isEmpty())
    }

    @Test
    fun `confirm calls repository cancel and keeps the item hidden`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(listOf(1L, 2L), awaitItem().downloads.map { it.id })

            vm.requestDelete(item(2))
            val hidden = awaitItem()
            val token = hidden.deleteSnackbarQueue.single().token

            vm.onDeleteSnackbarResult(token, undo = false)

            // Snackbar queue entry removed synchronously; the item is still
            // hidden because the pending delete has not been removed yet.
            val committed = awaitItem()
            assertEquals(listOf(1L), committed.downloads.map { it.id })
            assertTrue(committed.deleteSnackbarQueue.isEmpty())

            // Now run the commit coroutine (cancel → repo re-emission). The
            // commit's finally-update briefly re-renders the STALE pre-cancel
            // snapshot before the repository collector applies the new list;
            // the state converges on the repository emission.
            advanceUntilIdle()
            assertEquals(listOf(2L), repo.cancelled)
            assertEquals(listOf(1L), vm.uiState.value.downloads.map { it.id })
            assertTrue(vm.uiState.value.deleteSnackbarQueue.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(2L), repo.cancelled)
    }

    // ── Multiple pending deletes are independent ────────────────────────

    @Test
    fun `undoing one pending delete leaves the other hidden`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2), item(3)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(listOf(1L, 2L, 3L), awaitItem().downloads.map { it.id })

            vm.requestDelete(item(1))
            val first = awaitItem()
            val token1 = first.deleteSnackbarQueue.single().token

            vm.requestDelete(item(2))
            val both = awaitItem()
            assertEquals(listOf(3L), both.downloads.map { it.id })
            assertEquals(2, both.deleteSnackbarQueue.size)
            val token2 = both.deleteSnackbarQueue.last().token

            // Undo only the FIRST delete — item 2 must stay hidden.
            vm.onDeleteSnackbarResult(token1, undo = true)

            val afterUndo = awaitItem()
            assertEquals(listOf(1L, 3L), afterUndo.downloads.map { it.id })
            assertEquals(1, afterUndo.deleteSnackbarQueue.size)
            assertEquals(token2, afterUndo.deleteSnackbarQueue.single().token)

            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.cancelled.isEmpty())
    }

    // ── Re-requesting an already pending delete is a no-op ──────────────

    @Test
    fun `requesting delete of an already pending item is ignored`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            awaitItem()

            vm.requestDelete(item(1))
            val first = awaitItem()
            val token = first.deleteSnackbarQueue.single().token

            // Second request for the same item: state must stay unchanged.
            vm.requestDelete(item(1))
            expectNoEvents()
            assertEquals(1, vm.uiState.value.deleteSnackbarQueue.size)
            assertEquals(token, vm.uiState.value.deleteSnackbarQueue.single().token)
            assertEquals(listOf(2L), vm.uiState.value.downloads.map { it.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Multi-select delete clears selection ────────────────────────────

    @Test
    fun `multi-select delete hides all selected and clears selection`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2), item(3)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            awaitItem()

            vm.toggleSelection(1L)
            awaitItem() // selection = {1}
            vm.toggleSelection(2L)
            val selected = awaitItem() // selection = {1, 2}
            assertTrue(selected.isMultiSelectMode)
            assertEquals(setOf(1L, 2L), selected.selectedItems)

            vm.requestSelectedDelete()

            val state = awaitItem()
            assertEquals(listOf(3L), state.downloads.map { it.id })
            assertTrue(state.selectedItems.isEmpty())
            assertFalse(state.isMultiSelectMode)
            // Combined snackbar: itemCount=2, no single filename.
            val snackbar = state.deleteSnackbarQueue.single()
            assertEquals(2, snackbar.itemCount)
            assertNull(snackbar.fileName)

            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.cancelled.isEmpty())
    }
}
