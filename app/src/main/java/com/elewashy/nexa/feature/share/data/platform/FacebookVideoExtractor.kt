package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.client
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.decodeUrl
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.extractQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

internal class FacebookVideoExtractor : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing Facebook URL...")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
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

                FB_HD_RE.find(html)?.let { match ->
                    val videoUrl = decodeUrl(match.groupValues[1])
                    val quality = extractQuality(videoUrl) ?: "HD"
                    videos[quality] = videoUrl
                }

                FB_SD_RE.find(html)?.let { match ->
                    val videoUrl = decodeUrl(match.groupValues[1])
                    val quality = extractQuality(videoUrl) ?: "SD"
                    if (!videos.containsKey(quality)) videos[quality] = videoUrl
                }

                FB_BROWSER_NATIVE_RE.findAll(html).forEach { match ->
                    val videoUrl = decodeUrl(match.groupValues[1])
                    val quality = extractQuality(videoUrl)
                        ?: if (match.value.contains("hd", ignoreCase = true)) "HD" else "SD"
                    if (!videos.containsKey(quality)) videos[quality] = videoUrl
                }

                Log.d(TAG, "Found ${videos.size} video quality options")

                if (videos.isEmpty()) {
                    return@withContext VideoExtractor.ExtractionResult(
                        success = false,
                        error = "No video found in the page"
                    )
                }

                VideoExtractor.ExtractionResult(
                    success = true,
                    platform = "Facebook",
                    videos = videos
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Facebook video", e)
            VideoExtractor.ExtractionResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private companion object {
        const val TAG = "FacebookVideoExtractor"
        val FB_HD_RE = Regex(""""playable_url_quality_hd":"([^"]+)"""")
        val FB_SD_RE = Regex(""""playable_url":"([^"]+)"""")
        val FB_BROWSER_NATIVE_RE = Regex(""""browser_native_(?:hd|sd)_url":"([^"]+)"""")
    }
}
