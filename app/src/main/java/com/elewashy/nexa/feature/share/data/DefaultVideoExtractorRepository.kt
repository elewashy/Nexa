package com.elewashy.nexa.feature.share.data

import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [VideoExtractorRepository].
 *
 * [VideoExtractor] now routes to platform-specific implementations under
 * `data/platform`; [YouTubeExtractor] remains the conversion backend for
 * deferred YouTube downloads.
 */
@Singleton
class DefaultVideoExtractorRepository @Inject constructor() : VideoExtractorRepository {

    private val youTubeExtractor = YouTubeExtractor()
    private val videoExtractor = VideoExtractor(youTubeExtractor)

    override suspend fun extract(url: String) = videoExtractor.extract(url)

    override suspend fun fetchFileSize(url: String, referer: String): Long? =
        withContext(Dispatchers.IO) {
            ShareExtractionSupport.fetchFileSize(url, referer, TAG)
        }

    override suspend fun convertYouTubeVideo(resourceContent: String) =
        youTubeExtractor.convertVideo(resourceContent)

    private companion object {
        const val TAG = "VideoExtractorRepo"
    }
}
