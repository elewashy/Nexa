package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.MediaPageClassifier
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject

/**
 * X / Twitter posts.
 *
 * X's syndication endpoint (see [TwitterSyndication]) returns a tweet's
 * photos and progressive MP4 renditions directly from X's CDN in one small
 * JSON request, and is usually already cached from the browser's
 * download-button check. twitsave.com is only a fallback, for tweets the
 * endpoint does not serve or videos it lists without MP4 renditions.
 */
internal class TwitterVideoExtractor @Inject constructor(
    support: ShareExtractionSupport,
    private val tweets: TwitterSyndicationDataSource,
) : PageScraper(platformName = "Twitter/X", tag = TAG, support = support) {

    override val platform = SharePlatform.TWITTER

    override suspend fun extract(url: String): ExtractionResult {
        val tweetId = MediaPageClassifier.classify(url)?.takeIf { it.platform == SharePlatform.TWITTER }?.contentId
            ?: throw ExtractionException("Not a tweet URL", ExtractionError.UNSUPPORTED)

        val tweet = try {
            tweets.tweet(tweetId)
        } catch (e: IOException) {
            Log.w(TAG, "Syndication lookup failed, falling back: ${e.message}")
            null
        }

        if (tweet != null) {
            if (!tweet.hasMedia) throw ExtractionException("The post has no media")
            // Complete unless X reported a video without listing its MP4 renditions.
            if (!tweet.hasVideo || tweet.videos.isNotEmpty()) {
                return success(videoOptions(tweet.videos), tweet.photos)
            }
        }

        return success(fetchTwitsaveVideos(url), tweet?.photos.orEmpty())
    }

    private suspend fun fetchTwitsaveVideos(url: String): Map<String, String> {
        val request = Request.Builder()
            .url("$TWITSAVE_INFO_URL${URLEncoder.encode(url, "UTF-8")}")
            .header("User-Agent", ShareExtractionSupport.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = support.executeOrThrow(request).use { it.body.string() }
        val videos = linkedMapOf<String, String>()

        // Preferred: resolution-labelled download links.
        LI_RE.findAll(html).forEach { match ->
            videos[match.groupValues[2]] = ShareExtractionSupport.decodeBase64Url(match.groupValues[1], FILE_RE, TAG)
        }

        // Fallback: unlabelled download links.
        if (videos.isEmpty()) {
            DOWNLOAD_RE.findAll(html).forEachIndexed { index, match ->
                videos["Quality_${index + 1}"] = ShareExtractionSupport.decodeBase64Url(match.groupValues[1], FILE_RE, TAG)
            }
        }

        if (videos.isEmpty()) throw ExtractionException("No video found in this tweet")
        return videos
    }

    internal companion object {
        private const val TAG = "TwitterVideoExtractor"
        private const val TWITSAVE_INFO_URL = "https://twitsave.com/info?url="
        private val LI_RE = Regex(
            """<li>.*?href="(https://twitsave\.com/download\?file=[^"]+)".*?Video\s+Resolution:\s*(\d+x\d+).*?</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        private val DOWNLOAD_RE = Regex("""href="(https://twitsave\.com/download\?file=[^"]+)"""")
        private val FILE_RE = Regex("""file=([^&]+)""")

        /**
         * Quality options for the tweet's videos (see
         * [ShareExtractionSupport.multiVideoOptions]). Renditions are labelled
         * by resolution (`1280x720`, shown as 720p), or by their rank when X
         * reports no size.
         */
        fun videoOptions(videos: List<TweetVideo>): Map<String, String> {
            val labels = HashMap<String, String>()
            val versions = videos.map { video ->
                video.variants.mapIndexed { index, variant ->
                    val sized = variant.width > 0 && variant.height > 0
                    labels.putIfAbsent(variant.url, if (sized) "${variant.width}x${variant.height}" else "Quality_${index + 1}")
                    VideoVersion(variant.url, variant.width, variant.height)
                }
            }
            return ShareExtractionSupport.multiVideoOptions(versions) { labels.getValue(it.url) }
        }
    }
}
