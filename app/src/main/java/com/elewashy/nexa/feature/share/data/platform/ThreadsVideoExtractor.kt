package com.elewashy.nexa.feature.share.data.platform

import android.util.Log
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.client
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.decodeUrl
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.detectQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

internal class ThreadsVideoExtractor : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing Threads URL...")

        try {
            val postIdMatch = THREADS_POST_ID_RE.find(url)
            if (postIdMatch == null) {
                return@withContext VideoExtractor.ExtractionResult(
                    success = false,
                    error = "Invalid Threads URL"
                )
            }

            val postId = postIdMatch.groupValues[1]
            Log.d(TAG, "Post ID: $postId")

            val authority = if (url.contains("threads.com", ignoreCase = true)) {
                "www.threads.com"
            } else {
                "www.threads.net"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("authority", authority)
                .header("sec-fetch-dest", "document")
                .header("sec-fetch-mode", "navigate")
                .header("sec-fetch-site", "none")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext VideoExtractor.ExtractionResult(
                        success = false,
                        error = "Failed to fetch page: ${response.code}"
                    )
                }

                val html = response.body.string()
                val videos = linkedMapOf<String, String>()
                val seenUrls = mutableSetOf<String>()

                for (match in THREADS_VIDEO_VERSIONS_RE.findAll(html)) {
                    val versionsText = match.groupValues[1]
                    extractVideoCandidates(versionsText).forEach { candidate ->
                        val videoUrl = decodeUrl(candidate.url)
                        if (seenUrls.add(videoUrl)) {
                            videos.putUnique(detectQuality(videoUrl, context = candidate.context), videoUrl)
                        }
                    }
                    if (videos.isNotEmpty()) break
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
                    platform = "Threads",
                    videos = videos
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Threads video", e)
            VideoExtractor.ExtractionResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private fun extractVideoCandidates(versionsText: String): List<VideoCandidate> {
        return THREADS_URL_RE.findAll(versionsText)
            .map { match ->
                val start = versionsText.lastIndexOf('{', match.range.first).takeIf { it >= 0 } ?: 0
                val end = versionsText.indexOf('}', match.range.last).takeIf { it >= 0 }
                    ?: versionsText.length

                VideoCandidate(
                    url = match.groupValues[1],
                    context = versionsText.substring(start, end)
                )
            }
            .toList()
    }

    private fun LinkedHashMap<String, String>.putUnique(label: String, url: String) {
        if (!containsKey(label)) {
            put(label, url)
            return
        }

        var index = 2
        var uniqueLabel = "$label ($index)"
        while (containsKey(uniqueLabel)) {
            index++
            uniqueLabel = "$label ($index)"
        }
        put(uniqueLabel, url)
    }

    private data class VideoCandidate(
        val url: String,
        val context: String
    )

    private companion object {
        const val TAG = "ThreadsVideoExtractor"
        val THREADS_POST_ID_RE = Regex("/post/([^/?]+)")
        val THREADS_VIDEO_VERSIONS_RE = Regex("\"video_versions\":\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        val THREADS_URL_RE = Regex("\"url\":\"(https:\\\\?/\\\\?/[^\"]+\\.mp4[^\"]*)\"")
    }
}
