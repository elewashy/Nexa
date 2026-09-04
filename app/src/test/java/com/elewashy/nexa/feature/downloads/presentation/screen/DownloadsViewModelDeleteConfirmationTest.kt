package com.elewashy.nexa.feature.downloads.presentation.screen

import app.cash.turbine.test
import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.data.RenameDownloadResult
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
import com.elewashy.nexa.feature.downloads.domain.usecase.RenameDownloadUseCase
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the confirm-then-commit delete flow in [DownloadsViewModel]:
 * every delete entry point first raises `deleteConfirmation` without touching
 * the list or the repository; only `confirmDelete()` commits through `cancel()`.
 *
 * Uses kotlinx-coroutines-test (one shared [StandardTestDispatcher]: installed
 * as Main for the viewModelScope AND passed to `runTest` so both sides share a
 * scheduler) and Turbine to observe `uiState`. The repository is a fake built
 * on a `MutableStateFlow` so the VM's collectors see realistic emissions.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadsViewModelDeleteConfirmationTest {

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
        override suspend fun renameCompleted(id: Long, requestedName: String) =
            RenameDownloadResult.NotFound

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
        renameDownload = RenameDownloadUseCase(repository),
        applicationScope = appScope
    )

    // ── Single item ────────────────────────────────────────────────────

    @Test
    fun `requestDelete asks for confirmation and changes nothing else`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(listOf(1L, 2L), awaitItem().downloads.map { it.id })

            vm.requestDelete(item(1))

            val state = awaitItem()
            // The list is untouched until the user confirms.
            assertEquals(listOf(1L, 2L), state.downloads.map { it.id })
            val confirmation = state.deleteConfirmation
            assertNotNull(confirmation)
            assertEquals(1, confirmation!!.count)
            assertEquals("file_1.mp4", confirmation.singleFileName)

            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.cancelled.isEmpty())
    }

    @Test
    fun `dismissing the confirmation deletes nothing`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            awaitItem()
            vm.requestDelete(item(1))
            assertNotNull(awaitItem().deleteConfirmation)

            vm.dismissDeleteConfirmation()

            val state = awaitItem()
            assertNull(state.deleteConfirmation)
            assertEquals(listOf(1L, 2L), state.downloads.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertTrue("cancel() must not run without confirmation", repo.cancelled.isEmpty())
    }

    @Test
    fun `confirmDelete commits through cancel and closes the dialog`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2)))
        // The commit runs on the application scope; the test scope stands in for it so
        // advanceUntilIdle() deterministically drains the cancel() calls.
        val vm = viewModel(repo, this)
        advanceUntilIdle()

        vm.uiState.test {
            awaitItem()
            vm.requestDelete(item(2))
            awaitItem()

            vm.confirmDelete()

            assertNull(awaitItem().deleteConfirmation)
            advanceUntilIdle()
            assertEquals(listOf(2L), repo.cancelled)
            assertEquals(listOf(1L), vm.uiState.value.downloads.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirmDelete without a pending confirmation is a no-op`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.confirmDelete()
        advanceUntilIdle()

        assertTrue(repo.cancelled.isEmpty())
        assertEquals(listOf(1L), vm.uiState.value.downloads.map { it.id })
    }

    // ── Multi-select ───────────────────────────────────────────────────

    @Test
    fun `multi-select delete confirms every selected item then clears selection`() =
        runTest(testDispatcher) {
            val repo = FakeDownloadRepository(listOf(item(1), item(2), item(3)))
            val vm = viewModel(repo, this)
            advanceUntilIdle()

            vm.uiState.test {
                awaitItem()
                vm.toggleSelection(1L)
                awaitItem()
                vm.toggleSelection(2L)
                assertEquals(setOf(1L, 2L), awaitItem().selectedItems)

                vm.requestSelectedDelete()

                val asked = awaitItem()
                // Selection is kept while the dialog is open so Cancel returns to the same state.
                assertTrue(asked.isMultiSelectMode)
                assertEquals(setOf(1L, 2L), asked.selectedItems)
                val confirmation = asked.deleteConfirmation
                assertNotNull(confirmation)
                assertEquals(2, confirmation!!.count)
                assertNull(confirmation.singleFileName)
                assertTrue(repo.cancelled.isEmpty())

                vm.confirmDelete()

                val committed = awaitItem()
                assertNull(committed.deleteConfirmation)
                assertTrue(committed.selectedItems.isEmpty())
                assertFalse(committed.isMultiSelectMode)
                advanceUntilIdle()
                assertEquals(listOf(1L, 2L), repo.cancelled)
                assertEquals(listOf(3L), vm.uiState.value.downloads.map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `requestSelectedDelete with nothing selected shows no dialog`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            awaitItem()
            vm.requestSelectedDelete()
            expectNoEvents()
            assertNull(vm.uiState.value.deleteConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Repository changes while the dialog is open ────────────────────

    @Test
    fun `items removed elsewhere are pruned from an open confirmation`() = runTest(testDispatcher) {
        val repo = FakeDownloadRepository(listOf(item(1), item(2), item(3)))
        val vm = viewModel(repo, backgroundScope)
        advanceUntilIdle()

        vm.uiState.test {
            awaitItem()
            vm.toggleSelection(1L)
            awaitItem()
            vm.toggleSelection(2L)
            awaitItem()
            vm.requestSelectedDelete()
            assertEquals(2, awaitItem().deleteConfirmation?.count)

            // Item 1 disappears (e.g. removed from the notification) while the dialog is open.
            repo.remove(1L)

            val pruned = awaitItem()
            assertEquals(listOf(2L), pruned.deleteConfirmation?.items?.map { it.id })
            assertEquals("file_2.mp4", pruned.deleteConfirmation?.singleFileName)

            repo.remove(2L)

            // Nothing left to confirm: the dialog closes itself.
            assertNull(awaitItem().deleteConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.cancelled.isEmpty())
    }
}
