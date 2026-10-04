package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.MediaPageClassifier
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import javax.inject.Inject

/**
 * Threads posts, read from the structured data in the post page. The post's
 * data sits in the first ~200 KB of a ~1 MB page; the rest is never
 * downloaded.
 */
internal class ThreadsVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Threads", tag = "ThreadsVideoExtractor", support = support) {

    override val platform = SharePlatform.THREADS

    override suspend fun extract(url: String): ExtractionResult {
        val code = MediaPageClassifier.classify(url)?.takeIf { it.platform == SharePlatform.THREADS }?.contentId
            ?: throw ExtractionException("Not a Threads post URL", ExtractionError.UNSUPPORTED)

        val post = readPage(url) { MetaPostParser.scan(it, code) }
            ?: throw ExtractionException("Threads returned no data for this post")
        if (post.isEmpty) throw ExtractionException("The post has no media")

        val videos = ShareExtractionSupport.multiVideoOptions(post.videos) { version ->
            ShareExtractionSupport.detectQuality(version.url, version.width, version.height)
        }
        return success(videos, post.images)
    }
}
