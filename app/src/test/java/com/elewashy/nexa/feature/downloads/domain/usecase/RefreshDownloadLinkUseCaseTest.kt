package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadMetadata
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import com.elewashy.nexa.feature.share.domain.model.MediaImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RefreshDownloadLinkUseCaseTest {
    private class Extractor(var result: ExtractionResult) : VideoExtractorRepository {
        var calls = 0
        var conversion: String? = null
        var error: Exception? = null
        override suspend fun extract(url: String): ExtractionResult {
            calls++
            error?.let { throw it }
            return result
        }
        override suspend fun fetchFileSize(url: String, referer: String): Long? = null
        override suspend fun convertYouTubeVideo(resourceContent: String): String {
            conversion = resourceContent
            return "https://cdn.example/new"
        }
    }

    private fun item(key: String? = "720p") = DownloadItem(
        1, "https://cdn.example/old", "video.mp4", "/downloads/video.mp4",
        metadata = DownloadMetadata(sourcePageUrl = "https://example.com/post", mediaKey = key),
    )

    @Test fun `refresh selects original quality rather than first available`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", linkedMapOf(
            "1080p" to "https://cdn.example/wrong", "720p" to "https://cdn.example/new")))
        assertEquals(LinkRefreshResult.Success("https://cdn.example/new"), RefreshDownloadLinkUseCase(extractor)(item()))
    }

    @Test fun `missing selected quality is unavailable without fallback`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", mapOf("1080p" to "https://cdn.example/wrong")))
        assertEquals(LinkRefreshResult.Unavailable, RefreshDownloadLinkUseCase(extractor)(item()))
    }

    @Test fun `network failure and removed source have distinct outcomes`() = runBlocking {
        val extractor = Extractor(ExtractionResult.failure("offline", ExtractionError.NETWORK))
        val refresh = RefreshDownloadLinkUseCase(extractor)
        assertEquals(LinkRefreshResult.Failed, refresh(item()))
        extractor.result = ExtractionResult.failure("removed", ExtractionError.NO_MEDIA)
        assertEquals(LinkRefreshResult.Unavailable, refresh(item()))
    }

    @Test fun `conversion resolves new resource token`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", mapOf("720p" to MediaLabel.conversion("fresh-token"))))
        assertEquals(LinkRefreshResult.Success("https://cdn.example/new"), RefreshDownloadLinkUseCase(extractor)(item()))
        assertEquals("fresh-token", extractor.conversion)
    }

    @Test fun `legacy ambiguous selection never downloads another file`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", mapOf(
            "720p" to "https://cdn.example/a", "1080p" to "https://cdn.example/b")))
        assertEquals(LinkRefreshResult.Unavailable, RefreshDownloadLinkUseCase(extractor)(item(null)))
    }

    @Test fun `direct browser download without source does not invoke extraction`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", emptyMap()))
        assertEquals(LinkRefreshResult.Unsupported, RefreshDownloadLinkUseCase(extractor)(item().copy(metadata = DownloadMetadata())))
        assertEquals(0, extractor.calls)
    }

    @Test fun `stable redirect URL can replace expired resolved URL`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", emptyMap()))
        val download = item().copy(metadata = DownloadMetadata(resolvedUrl = "https://cdn.example/expired"))
        assertEquals(LinkRefreshResult.Success(download.url), RefreshDownloadLinkUseCase(extractor)(download))
        assertEquals(0, extractor.calls)
    }

    @Test fun `quality size changes do not lose selected rendition`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", mapOf("720p - 20.0 MB" to "https://cdn.example/new")))
        assertEquals(LinkRefreshResult.Success("https://cdn.example/new"), RefreshDownloadLinkUseCase(extractor)(item("720p - 19.9 MB")))
    }

    @Test fun `image refresh preserves position and rejects changed carousel size`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", emptyMap(), listOf(
            MediaImage("https://cdn.example/first"), MediaImage("https://cdn.example/second"))))
        val download = item(null).copy(metadata = DownloadMetadata(
            sourcePageUrl = "https://example.com/post", imageIndex = 1, imageCount = 2))
        val refresh = RefreshDownloadLinkUseCase(extractor)
        assertEquals(LinkRefreshResult.Success("https://cdn.example/second"), refresh(download))
        extractor.result = extractor.result.copy(images = listOf(MediaImage("https://cdn.example/other")))
        assertEquals(LinkRefreshResult.Unavailable, refresh(download))
    }

    @Test fun `invalid refreshed URL is rejected`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", mapOf("720p" to "file:///private/file")))
        assertEquals(LinkRefreshResult.Failed, RefreshDownloadLinkUseCase(extractor)(item()))
    }

    @Test fun `cancellation propagates instead of becoming a failure`() = runBlocking {
        val extractor = Extractor(ExtractionResult.success("test", emptyMap()))
        extractor.error = CancellationException("cancelled")
        try {
            RefreshDownloadLinkUseCase(extractor)(item())
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
