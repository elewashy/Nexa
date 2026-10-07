package com.elewashy.nexa.feature.share.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaImage
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `second tap on download does not start another download`() = runTest {
        val viewModel = createViewModel()
        val quality = VideoQuality(quality = "720p", url = VIDEO_URL)

        viewModel.onQualitySelected(quality)
        viewModel.onQualitySelected(quality)

        assertEquals(1, shadowOf(context).allStartedServices.size)
    }

    @Test
    fun `only the first tap emits a close event`() = runTest {
        val viewModel = createViewModel()
        val quality = VideoQuality(quality = "720p", url = VIDEO_URL)

        viewModel.events.test {
            viewModel.onQualitySelected(quality)
            viewModel.onQualitySelected(quality)

            assertTrue(awaitItem() is ShareEvent.Close)
            expectNoEvents()
        }
    }

    @Test
    fun `selecting a quality hides the sheet immediately`() = runTest {
        val viewModel = createViewModel()
        viewModel.handleSharedText("https://example.com/watch?v=1")
        assertTrue(viewModel.uiState.value.showSheet)

        viewModel.onQualitySelected(VideoQuality(quality = "720p", url = VIDEO_URL))

        assertFalse(viewModel.uiState.value.showSheet)
    }

    @Test
    fun `image-only posts open the sheet with every image selected`() = runTest {
        val viewModel = createViewModel(ExtractionResult.success("Instagram", emptyMap(), IMAGES))

        viewModel.handleSharedText("https://www.instagram.com/p/ABC/")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showSheet)
        assertFalse(state.isLoading)
        assertEquals(IMAGES, state.images)
        assertEquals(IMAGES.map { it.url }.toSet(), state.selectedImageUrls)
    }

    @Test
    fun `images can be toggled individually and all at once`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.onImageSelectionToggled(IMAGES[1])
        assertEquals(setOf(IMAGES[0].url, IMAGES[2].url), viewModel.uiState.value.selectedImageUrls)

        viewModel.onAllImagesSelectionToggled()
        assertEquals(IMAGES.map { it.url }.toSet(), viewModel.uiState.value.selectedImageUrls)

        viewModel.onAllImagesSelectionToggled()
        assertTrue(viewModel.uiState.value.selectedImageUrls.isEmpty())

        viewModel.onImageSelectionToggled(IMAGES[2])
        assertEquals(setOf(IMAGES[2].url), viewModel.uiState.value.selectedImageUrls)
    }

    @Test
    fun `downloading starts one download per selected image only once`() = runTest {
        val viewModel = loadedViewModel()
        viewModel.onImageSelectionToggled(IMAGES[0])

        viewModel.events.test {
            viewModel.onDownloadSelectedImages()
            viewModel.onDownloadSelectedImages()
            assertTrue(awaitItem() is ShareEvent.Close)
            expectNoEvents()
        }

        val started = shadowOf(context).allStartedServices
        assertEquals(listOf(IMAGES[1].url, IMAGES[2].url), started.map { it.getStringExtra(DownloadService.EXTRA_URL) })
        assertEquals(listOf("image/png", "image/jpeg"), started.map { it.getStringExtra(DownloadService.EXTRA_MIME_TYPE) })
        assertTrue(started.all { it.getStringExtra(DownloadService.EXTRA_FILE_NAME)!!.startsWith("Instagram_image_") })
        assertFalse(viewModel.uiState.value.showSheet)
    }

    @Test
    fun `nothing is downloaded when no image is selected`() = runTest {
        val viewModel = loadedViewModel()
        viewModel.onAllImagesSelectionToggled()

        viewModel.onDownloadSelectedImages()

        assertTrue(shadowOf(context).allStartedServices.isEmpty())
        assertTrue(viewModel.uiState.value.showSheet)
    }

    @Test
    fun `dismissing the sheet forgets the session so the same page can be opened again`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.onSheetDismissed()
        assertFalse(viewModel.uiState.value.showSheet)

        viewModel.handleSharedText("https://www.instagram.com/p/ABC/")
        assertTrue(viewModel.uiState.value.isLoading)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(IMAGES, viewModel.uiState.value.images)
    }

    @Test
    fun `a new session can download again after a previous download`() = runTest {
        val viewModel = loadedViewModel()
        viewModel.onQualitySelected(VideoQuality(quality = "720p", url = VIDEO_URL))

        viewModel.handleSharedText("https://www.instagram.com/p/ABC/")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSheet)
        viewModel.onQualitySelected(VideoQuality(quality = "720p", url = VIDEO_URL))

        assertEquals(2, shadowOf(context).allStartedServices.size)
    }

    @Test
    fun `re-delivering the open page keeps the loaded sheet`() = runTest {
        val viewModel = loadedViewModel()
        viewModel.onImageSelectionToggled(IMAGES[0])

        viewModel.handleSharedText("https://www.instagram.com/p/ABC/")

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(IMAGES[0].url in viewModel.uiState.value.selectedImageUrls)
    }

    @Test
    fun `results without media close the sheet with the no-media message`() = runTest {
        assertEquals(
            context.getString(R.string.share_error_no_media),
            closeMessageFor(ExtractionResult.success("X", emptyMap(), emptyList())),
        )
    }

    @Test
    fun `failures tell the user what went wrong`() = runTest {
        assertEquals(
            context.getString(R.string.share_error_network),
            closeMessageFor(ExtractionResult.failure("timeout", ExtractionError.NETWORK)),
        )
        assertEquals(
            context.getString(R.string.share_error_unsupported),
            closeMessageFor(ExtractionResult.failure("bad link", ExtractionError.UNSUPPORTED)),
        )
        assertEquals(
            context.getString(R.string.share_error_no_media),
            closeMessageFor(ExtractionResult.failure("text post", ExtractionError.NO_MEDIA)),
        )
    }

    private suspend fun closeMessageFor(result: ExtractionResult): String? {
        val viewModel = createViewModel(result)
        var message: String? = null
        viewModel.events.test {
            viewModel.handleSharedText("https://x.com/u/status/1")
            testDispatcher.scheduler.advanceUntilIdle()
            message = (awaitItem() as ShareEvent.Close).message
        }
        return message
    }

    private suspend fun loadedViewModel(): ShareViewModel {
        val viewModel = createViewModel(ExtractionResult.success("Instagram", emptyMap(), IMAGES))
        viewModel.handleSharedText("https://www.instagram.com/p/ABC/")
        testDispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private fun createViewModel(
        result: ExtractionResult = ExtractionResult.failure("unused"),
    ): ShareViewModel = ShareViewModel(
        appContext = context,
        videoExtractorRepository = object : VideoExtractorRepository {
            override suspend fun extract(url: String) = result
            override suspend fun fetchFileSize(url: String, referer: String): Long? = null
            override suspend fun convertYouTubeVideo(resourceContent: String) = "unused"
        },
        applicationScope = CoroutineScope(SupervisorJob() + testDispatcher),
    )

    private companion object {
        const val VIDEO_URL = "https://cdn.example.com/video.mp4"

        val IMAGES = listOf(
            MediaImage("https://cdn.example.com/1.jpg", 1080, 1350),
            MediaImage("https://cdn.example.com/2.png?x=1", 1080, 1080),
            MediaImage("https://pbs.twimg.com/media/C?format=jpg&name=orig"),
        )
    }
}
