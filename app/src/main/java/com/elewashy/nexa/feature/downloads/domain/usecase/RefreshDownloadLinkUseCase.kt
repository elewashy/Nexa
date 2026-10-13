package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

sealed interface LinkRefreshResult {
    data class Success(val url: String) : LinkRefreshResult
    data object Unavailable : LinkRefreshResult
    data object Failed : LinkRefreshResult
    data object Unsupported : LinkRefreshResult
}

private fun stableKey(key: String): String = key.replace(Regex(" - [0-9]+(?:\\.[0-9]+)? MB$"), "")

class RefreshDownloadLinkUseCase @Inject constructor(
    private val extractor: VideoExtractorRepository,
) {
    suspend operator fun invoke(item: DownloadItem): LinkRefreshResult {
        val metadata = item.metadata
        val page = metadata.sourcePageUrl ?: item.referer?.takeIf { item.source == "SHARE_FROM_APP" }
            ?: return if (metadata.resolvedUrl != null && metadata.resolvedUrl != item.url &&
                item.url.toHttpUrlOrNull() != null
            ) LinkRefreshResult.Success(item.url) else LinkRefreshResult.Unsupported
        if (page.toHttpUrlOrNull() == null) return LinkRefreshResult.Unsupported
        return try {
            withTimeout(60_000) {
                val result = extractor.extract(page)
                if (!result.success) {
                    return@withTimeout when (result.failure) {
                        ExtractionError.NETWORK -> LinkRefreshResult.Failed
                        ExtractionError.UNSUPPORTED -> LinkRefreshResult.Unsupported
                        else -> LinkRefreshResult.Unavailable
                    }
                }
                val candidate = when {
                    metadata.mediaKey != null -> result.videos[metadata.mediaKey]
                        ?: result.videos.entries.filter { stableKey(it.key) == stableKey(metadata.mediaKey) }
                            .singleOrNull()?.value
                    metadata.imageIndex != null -> {
                        // Do not silently select another image when a post's media set changed.
                        if (result.images.size != metadata.imageCount) null
                        else result.images.getOrNull(metadata.imageIndex)?.url
                    }
                    else -> {
                        val urls = result.videos.values + result.images.map { it.url }
                        urls.firstOrNull { it == item.url || it == metadata.resolvedUrl }
                            ?: urls.singleOrNull()
                    }
                } ?: return@withTimeout LinkRefreshResult.Unavailable
                val url = if (candidate.startsWith(MediaLabel.CONVERT_PREFIX)) {
                    extractor.convertYouTubeVideo(candidate.removePrefix(MediaLabel.CONVERT_PREFIX))
                } else candidate
                if (url.toHttpUrlOrNull() == null) LinkRefreshResult.Failed
                else LinkRefreshResult.Success(url)
            }
        } catch (_: TimeoutCancellationException) {
            LinkRefreshResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LinkRefreshResult.Failed
        }
    }
}
