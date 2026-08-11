package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Delegates to twitsave.com, which resolves the tweet's media server-side.
 */
internal class TwitterVideoExtractor @Inject constructor(
    support: ShareExtractionSupport
) : PageScraper(platformName = "Twitter/X", tag = "TwitterVideoExtractor", support = support) {

    override val platform = SharePlatform.TWITTER

    override fun extractFromPage(url: String): ExtractionResult {
        TWEET_ID_RE.find(url)?.groupValues?.get(1)?.let { Log.d(TAG, "Tweet ID: $it") }

        val twitsaveUrl = "$TWITSAVE_INFO_URL${URLEncoder.encode(url, "UTF-8")}"
        val request = Request.Builder()
            .url(twitsaveUrl)
            .header("User-Agent", ShareExtractionSupport.USER_AGENT_DESKTOP)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        support.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ExtractionException("Failed to fetch page: ${response.code}")
            }

            val html = response.body.string()
            val videos = mutableMapOf<String, String>()

            // Preferred: resolution-labelled download links.
            LI_RE.findAll(html).forEach { match ->
                videos[match.groupValues[2]] =
                    ShareExtractionSupport.decodeBase64Url(match.groupValues[1], FILE_RE, TAG)
            }

            // Fallback: unlabelled download links.
            if (videos.isEmpty()) {
                var index = 1
                DOWNLOAD_RE.findAll(html).forEach { match ->
                    videos["Quality_$index"] =
                        ShareExtractionSupport.decodeBase64Url(match.groupValues[1], FILE_RE, TAG)
                    index++
                }
            }

            if (videos.isEmpty()) {
                throw ExtractionException("No video found in this tweet")
            }
            return success(videos)
        }
    }

    private companion object {
        const val TAG = "TwitterVideoExtractor"
        const val TWITSAVE_INFO_URL = "https://twitsave.com/info?url="
        val TWEET_ID_RE = Regex("""/status/(\d+)""")
        val LI_RE = Regex(
            """<li>.*?href="(https://twitsave\.com/download\?file=[^"]+)".*?Video\s+Resolution:\s*(\d+x\d+).*?</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val DOWNLOAD_RE = Regex("""href="(https://twitsave\.com/download\?file=[^"]+)"""")
        val FILE_RE = Regex("""file=([^&]+)""")
    }
}
