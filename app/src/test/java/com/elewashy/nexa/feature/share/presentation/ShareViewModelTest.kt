package com.elewashy.nexa.feature.share.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private fun createViewModel(): ShareViewModel = ShareViewModel(
        appContext = context,
        videoExtractorRepository = object : VideoExtractorRepository {
            override suspend fun extract(url: String) = ExtractionResult.failure("unused")
            override suspend fun fetchFileSize(url: String, referer: String): Long? = null
            override suspend fun convertYouTubeVideo(resourceContent: String) = "unused"
        },
        applicationScope = CoroutineScope(SupervisorJob() + testDispatcher),
    )

    private companion object {
        const val VIDEO_URL = "https://cdn.example.com/video.mp4"
    }
}
