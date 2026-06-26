package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.client
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.decodeBase64Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

internal class TwitterVideoExtractor : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing Twitter/X URL...")

        try {
            if (!url.contains("twitter.com") && !url.contains("x.com")) {
                return@withContext VideoExtractor.ExtractionResult(
                    success = false,
                    error = "Invalid Twitter/X URL"
                )
            }

            val tweetIdMatch = TWITTER_TWEET_ID_RE.find(url)
            val tweetId = tweetIdMatch?.groupValues?.get(1) ?: "Unknown"
            Log.d(TAG, "Tweet ID: $tweetId")

            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val twitsaveUrl = "https://twitsave.com/info?url=$encodedUrl"
            Log.d(TAG, "Fetching from twitsave.com...")

            val request = Request.Builder()
                .url(twitsaveUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext VideoExtractor.ExtractionResult(
                        success = false,
                        error = "Failed to fetch page: ${response.code}"
                    )
                }

                val html = response.body.string()
                val videos = mutableMapOf<String, String>()

                TWITTER_LI_RE.findAll(html).forEach { match ->
                    val downloadUrl = match.groupValues[1]
                    val resolution = match.groupValues[2]
                    val decodedUrl = decodeBase64Url(downloadUrl, TWITTER_FILE_RE, TAG)
                    videos[resolution] = decodedUrl
                }

                if (videos.isEmpty()) {
                    var qualityIndex = 1
                    TWITTER_DOWNLOAD_RE.findAll(html).forEach { match ->
                        val downloadUrl = match.groupValues[1]
                        val decodedUrl = decodeBase64Url(downloadUrl, TWITTER_FILE_RE, TAG)
                        videos["Quality_$qualityIndex"] = decodedUrl
                        qualityIndex++
                    }
                }

                Log.d(TAG, "Found ${videos.size} video quality options")

                if (videos.isEmpty()) {
                    return@withContext VideoExtractor.ExtractionResult(
                        success = false,
                        error = "No video found in this tweet"
                    )
                }

                VideoExtractor.ExtractionResult(
                    success = true,
                    platform = "Twitter/X",
                    videos = videos
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Twitter video", e)
            VideoExtractor.ExtractionResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private companion object {
        const val TAG = "TwitterVideoExtractor"
        val TWITTER_TWEET_ID_RE = Regex("""/status/(\d+)""")
        val TWITTER_LI_RE = Regex(
            """<li>.*?href="(https://twitsave\.com/download\?file=[^"]+)".*?Video\s+Resolution:\s*(\d+x\d+).*?</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val TWITTER_DOWNLOAD_RE = Regex("""href="(https://twitsave\.com/download\?file=[^"]+)"""")
        val TWITTER_FILE_RE = Regex("""file=([^&]+)""")
    }
}
