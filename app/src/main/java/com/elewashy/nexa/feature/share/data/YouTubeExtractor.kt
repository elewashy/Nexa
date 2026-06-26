package com.elewashy.nexa.feature.share.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * YouTubeExtractor - Extract video links from YouTube using VidsSave API
 * Based on the Python vidssave_downloader.py implementation
 */
class YouTubeExtractor {
    
    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val BASE_URL = "https://api.vidssave.com/api/contentsite_api"
        private const val VIDSSAVE_SITE = "https://vidssave.com"
        private const val CHUNK_PATTERN = "/_next/static/chunks/9864-[a-f0-9]+\\.js"
        
        // Fallback values if auth fetch fails
        private const val FALLBACK_AUTH = "20250901majwlqo"
        private const val FALLBACK_DOMAIN = "api-ak.vidssave.com"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    private var auth: String = FALLBACK_AUTH
    private var domain: String = FALLBACK_DOMAIN
    private val authMutex = Mutex()
    
    /**
     * Result data class for YouTube extraction
     */
    data class YouTubeResult(
        val success: Boolean,
        val title: String? = null,
        val duration: Int? = null,
        val videos: Map<String, VideoInfo> = emptyMap(),
        val error: String? = null
    )
    
    data class VideoInfo(
        val url: String,
        val quality: String,
        val size: Long? = null,
        val format: String? = null,
        val type: MediaType = MediaType.VIDEO,
        val isDirect: Boolean = false,
        val resourceContent: String? = null // For on-demand conversion
    )
    
    enum class MediaType {
        VIDEO,
        AUDIO
    }
    
    private var authFetched: Boolean = false
    
    /**
     * Ensure auth is fetched (lazy initialization on background thread)
     */
    private suspend fun ensureAuthFetched() = withContext(Dispatchers.IO) {
        if (authFetched) return@withContext

        authMutex.withLock {
            if (!authFetched) {
                fetchAuthFromSite()
                authFetched = true
            }
        }
    }
    
    /**
     * Fetch auth token and domain from VidsSave website
     */
    private fun fetchAuthFromSite() {
        try {
            Log.d(TAG, "Fetching auth token from VidsSave...")
            
            // Get main page to find JS chunk URL
            val mainRequest = Request.Builder()
                .url(VIDSSAVE_SITE)
                .build()
            
            val mainHtml = client.newCall(mainRequest).execute().use { response ->
                response.body.string()
            }
            
            // Find chunk URL
            val chunkRegex = Regex(CHUNK_PATTERN)
            val chunkMatch = chunkRegex.find(mainHtml)
            val chunkUrl = if (chunkMatch != null) {
                "$VIDSSAVE_SITE${chunkMatch.value}"
            } else {
                "$VIDSSAVE_SITE/_next/static/chunks/9864-ae165a64347d921f.js"
            }
            
            Log.d(TAG, "Fetching from: $chunkUrl")
            
            // Fetch JS chunk
            val jsRequest = Request.Builder()
                .url(chunkUrl)
                .build()
            
            val jsContent = client.newCall(jsRequest).execute().use { response ->
                response.body.string()
            }
            
            // Extract auth token
            val authRegex = Regex("""auth:\s*["']([^"']+)["']""")
            val authMatch = authRegex.find(jsContent)
            if (authMatch != null) {
                auth = authMatch.groupValues[1]
                Log.d(TAG, "✓ Auth token found: $auth")
            }
            
            // Extract domain
            val domainRegex = Regex("""domain:\s*["']([^"']+)["']""")
            val domainMatch = domainRegex.find(jsContent)
            if (domainMatch != null) {
                domain = domainMatch.groupValues[1]
                Log.d(TAG, "✓ Domain found: $domain")
            } else {
                // Try alternative pattern
                val domainConstRegex = Regex("""VIDEODOWNLOAD:\s*["']([^"']+)["']""")
                val domainConstMatch = domainConstRegex.find(jsContent)
                if (domainConstMatch != null) {
                    domain = domainConstMatch.groupValues[1]
                    Log.d(TAG, "✓ Domain found: $domain")
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠ Could not fetch auth from site: ${e.message}")
            Log.w(TAG, "Using fallback values...")
        }
    }
    
    /**
     * Extract YouTube video information and download links
     * Only shows available formats - conversion happens on-demand when user selects
     */
    suspend fun extract(url: String): YouTubeResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Extracting YouTube video: $url")
        
        // Ensure auth is fetched first
        ensureAuthFetched()
        
        try {
            // Step 1: Parse video to get available resources
            val videoData = parseVideo(url)
            
            val title = videoData.optString("title")
            val duration = videoData.optInt("duration", 0)
            
            Log.d(TAG, "Title: $title")
            Log.d(TAG, "Duration: ${duration}s")
            
            // Step 2: Extract resources
            val resources = videoData.optJSONArray("resources")
            if (resources == null || resources.length() == 0) {
                return@withContext YouTubeResult(
                    success = false,
                    error = "No video resources found"
                )
            }
            
            val videos = mutableMapOf<String, VideoInfo>()
            
            for (i in 0 until resources.length()) {
                val resource = resources.getJSONObject(i)
                val type = resource.optString("type")
                val quality = resource.optString("quality")
                val format = resource.optString("format")
                val size = resource.optLong("size", 0)
                val downloadMode = resource.optString("download_mode")
                val resourceContent = resource.optString("resource_content")
                
                val mediaType = if (type == "audio") MediaType.AUDIO else MediaType.VIDEO
                
                // Check if direct download is available
                if (downloadMode == "direct") {
                    val downloadUrl = resource.optString("download_url")
                    if (downloadUrl.isNotEmpty()) {
                        val key = if (mediaType == MediaType.AUDIO) {
                            "AUDIO:$quality"
                        } else {
                            quality
                        }
                        videos[key] = VideoInfo(
                            url = downloadUrl,
                            quality = quality,
                            size = size,
                            format = format,
                            type = mediaType,
                            isDirect = true,
                            resourceContent = null
                        )
                        Log.d(TAG, "Direct download available: $quality ($format)")
                    }
                } else {
                    // Need conversion - store resource_content for later
                    if (resourceContent.isNotEmpty()) {
                        val key = if (mediaType == MediaType.AUDIO) {
                            "AUDIO:$quality"
                        } else {
                            quality
                        }
                        // Store placeholder URL with resource_content for on-demand conversion
                        videos[key] = VideoInfo(
                            url = "", // Empty URL - will be converted on-demand
                            quality = quality,
                            size = size,
                            format = format,
                            type = mediaType,
                            isDirect = false,
                            resourceContent = resourceContent
                        )
                        Log.d(TAG, "Conversion available: $quality ($format)")
                    }
                }
            }
            
            if (videos.isEmpty()) {
                return@withContext YouTubeResult(
                    success = false,
                    error = "Failed to extract any video links"
                )
            }
            
            Log.d(TAG, "Found ${videos.size} video/audio options (instant)")
            
            YouTubeResult(
                success = true,
                title = title,
                duration = duration,
                videos = videos
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube video", e)
            YouTubeResult(
                success = false,
                error = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Convert a video on-demand when user selects it
     * This is called only when user clicks download on a non-direct format
     */
    suspend fun convertVideo(resourceContent: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Converting video on-demand...")
        return@withContext requestAndMonitorDownload(resourceContent)
    }
    
    /**
     * Parse video URL to get available resources
     */
    private fun parseVideo(videoUrl: String): JSONObject {
        val url = "$BASE_URL/media/parse"
        
        val formBody = FormBody.Builder()
            .add("auth", auth)
            .add("domain", domain)
            .add("origin", "source")
            .add("link", videoUrl)
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .header("Referer", VIDSSAVE_SITE)
            .header("Origin", VIDSSAVE_SITE)
            .build()
        
        val jsonResponse = client.newCall(request).execute().use { response ->
            response.body.string()
        }
        val json = JSONObject(jsonResponse)
        
        if (json.optInt("status") != 1) {
            throw Exception("Parse failed: ${json.optString("msg", "Unknown error")}")
        }
        
        return json.optJSONObject("data") ?: JSONObject()
    }
    
    /**
     * Request download conversion and monitor progress
     */
    private fun requestAndMonitorDownload(resourceContent: String): String {
        // Step 1: Request download
        val taskId = requestDownload(resourceContent)
        Log.d(TAG, "Task ID: $taskId")
        
        // Step 2: Monitor conversion via SSE
        Log.d(TAG, "Monitoring conversion...")
        return monitorDownload(taskId)
    }
    
    /**
     * Request download conversion
     */
    private fun requestDownload(resourceContent: String): String {
        val url = "$BASE_URL/media/download"
        
        val formBody = FormBody.Builder()
            .add("auth", auth)
            .add("domain", domain)
            .add("request", resourceContent)
            .add("no_encrypt", "1")
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .header("Referer", VIDSSAVE_SITE)
            .header("Origin", VIDSSAVE_SITE)
            .build()
        
        val jsonResponse = client.newCall(request).execute().use { response ->
            response.body.string()
        }
        val json = JSONObject(jsonResponse)
        
        if (json.optInt("status") != 1) {
            throw Exception("Download request failed: $jsonResponse")
        }
        
        return json.optJSONObject("data")?.optString("task_id") 
            ?: throw Exception("No task_id in response")
    }
    
    /**
     * Monitor download conversion via SSE
     */
    private fun monitorDownload(taskId: String, timeoutSeconds: Int = 300): String {
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("api.vidssave.com")
            .addPathSegments("sse/contentsite_api/media/download_query")
            .addQueryParameter("auth", auth)
            .addQueryParameter("domain", domain)
            .addQueryParameter("task_id", taskId)
            .addQueryParameter("download_domain", "vidssave.com")
            .addQueryParameter("origin", "content_site")
            .build()

        val request = Request.Builder()
            .url(urlBuilder)
            .header("Accept", "text/event-stream")
            .header("Referer", VIDSSAVE_SITE)
            .header("Origin", VIDSSAVE_SITE)
            .build()

        val startTime = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("SSE request failed: HTTP ${response.code}")
            }

            response.body.byteStream().use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var eventType = ""

                reader.lineSequence().forEach { line ->
                    if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000) {
                        throw Exception("Download conversion timeout")
                    }

                    when {
                        line.startsWith("event:") -> {
                            eventType = line.substringAfter("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            val dataStr = line.substringAfter("data:").trim()
                            try {
                                val data = JSONObject(dataStr)

                                when (eventType) {
                                    "success" -> {
                                        val downloadLink = data.optString("download_link")
                                        if (downloadLink.isNotEmpty()) {
                                            return downloadLink
                                        }
                                    }
                                    "failed" -> {
                                        throw Exception("Download conversion failed: $dataStr")
                                    }
                                    "running" -> {
                                        val progress = data.optInt("progress", 0)
                                        Log.d(TAG, "Progress: $progress%")
                                    }
                                }
                            } catch (e: JSONException) {
                                // Ignore JSON parse errors for non-JSON data lines
                            }
                        }
                    }
                }
            }
        }

        throw Exception("SSE stream ended without success")
    }
}
