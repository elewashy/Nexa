package com.elewashy.nexa.feature.share.data.platform

import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import com.elewashy.nexa.feature.share.data.VideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.client
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.decodeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder

internal class InstagramVideoExtractor : PlatformVideoExtractor {

    override suspend fun extract(url: String): VideoExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing Instagram URL...")

        try {
            val shortcodeMatch = IG_SHORTCODE_RE.find(url)
            if (shortcodeMatch == null) {
                return@withContext VideoExtractor.ExtractionResult(
                    success = false,
                    error = "Invalid Instagram URL"
                )
            }

            val shortcode = shortcodeMatch.groupValues[2]
            Log.d(TAG, "Video ID: $shortcode")

            val finalUrl = if (url.endsWith('/')) url else "$url/"
            val request = Request.Builder()
                .url(finalUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
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
                val videos = mutableMapOf<String, String>()
                val seenUrls = mutableSetOf<String>()

                // Method 1: Match by shortcode (need dynamic pattern with shortcode)
                val shortcodePattern = Regex(""""code":"$shortcode".*?"video_versions":\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                val shortcodeVideoMatch = shortcodePattern.find(html)
                shortcodeVideoMatch?.let { match ->
                    try {
                        val versionsJson = "[${match.groupValues[1]}]"
                        val jsonArray = JSONArray(versionsJson)
                        for (i in 0 until jsonArray.length()) {
                            val version = jsonArray.getJSONObject(i)
                            if (version.has("url")) {
                                val videoUrl = decodeUrl(version.getString("url"))
                                if (!seenUrls.add(videoUrl)) continue
                                val width = version.optInt("width", 0)
                                val height = version.optInt("height", 0)
                                val quality = when {
                                    width > 0 && height > 0 -> "${width}x${height}"
                                    width > 0 -> "${width}p"
                                    else -> extractResolutionFromUrl(videoUrl) ?: "video"
                                }
                                videos[quality] = videoUrl
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Instagram JSON", e)
                    }
                }

                // Method 2: Fallback - first video_versions found
                if (videos.isEmpty()) {
                    val fallbackMatch = IG_VIDEO_VERSIONS_FALLBACK_RE.find(html)
                    fallbackMatch?.let { match ->
                        val videoUrl = decodeUrl(match.groupValues[1])
                        val quality = "${match.groupValues[2]}x${match.groupValues[3]}"
                        videos[quality] = videoUrl
                    }
                }

                // Add audio option eagerly — the URL is deterministic.
                // Validation is deferred; if the URL is invalid the download will
                // fail gracefully, which is acceptable for a non-critical option.
                if (videos.isNotEmpty()) {
                    val firstVideoUrl = videos.values.first()
                    val encodedUrl = URLEncoder.encode(firstVideoUrl, "UTF-8")
                    val audioUrl = "https://mp3.videodropper.app/api?url=$encodedUrl"
                    videos["AUDIO:Audio"] = audioUrl
                    Log.d(TAG, "Audio option added (deferred validation)")
                }

                Log.d(TAG, "Found ${videos.size} media options")

                if (videos.isEmpty()) {
                    return@withContext VideoExtractor.ExtractionResult(
                        success = false,
                        error = "No video found in the page"
                    )
                }

                VideoExtractor.ExtractionResult(
                    success = true,
                    platform = "Instagram",
                    videos = videos
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Instagram video", e)
            VideoExtractor.ExtractionResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }

    private fun extractResolutionFromUrl(videoUrl: String): String? {
        val encodedMetadata = runCatching { videoUrl.toUri().getQueryParameter("efg") }.getOrNull()
            ?: return null

        return runCatching {
            val decodedParam = URLDecoder.decode(encodedMetadata, "UTF-8")
            val metadata = String(Base64.decode(decodedParam, Base64.URL_SAFE), Charsets.UTF_8)
            IG_ENCODE_TAG_RESOLUTION_RE.find(metadata)?.groupValues?.get(1)?.let { "${it}p" }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "InstagramVideoExtractor"
        val IG_SHORTCODE_RE = Regex("""/(p|reels?|tv)/([^/?]+)""")
        val IG_ENCODE_TAG_RESOLUTION_RE = Regex("""\.(\d{3,4})\.""")
        val IG_VIDEO_VERSIONS_FALLBACK_RE = Regex(""""video_versions":\s*\[\s*\{[^\]]*?"url":"([^"]+)"[^\]]*?"width":(\d+)[^\]]*?"height":(\d+)""")
    }
}
