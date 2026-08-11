package com.elewashy.nexa.feature.share.data

import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultVideoExtractorRepository @Inject constructor(
    private val videoExtractor: VideoExtractor,
    private val youTubeExtractor: YouTubeExtractor,
    private val support: ShareExtractionSupport
) : VideoExtractorRepository {

    override suspend fun extract(url: String): ExtractionResult = videoExtractor.extract(url)

    override suspend fun fetchFileSize(url: String, referer: String): Long? =
        withContext(Dispatchers.IO) { support.fetchFileSize(url, referer) }

    override suspend fun convertYouTubeVideo(resourceContent: String): String =
        youTubeExtractor.convertVideo(resourceContent)
}
