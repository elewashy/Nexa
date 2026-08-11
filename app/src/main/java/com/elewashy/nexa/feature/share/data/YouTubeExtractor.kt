package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * YouTube extraction via the VidsSave API.
 *
 * The API requires an auth token and domain that are scraped from the VidsSave
 * site once (with hardcoded fallbacks), then cached for the process lifetime.
 * Non-direct formats are converted on demand via SSE polling only when the
 * user actually selects them.
 */
class YouTubeExtractor(httpClientProvider: HttpClientProvider) {

    data class MediaOption(
        val url: String,
        val sizeBytes: Long?,
        val isDirect: Boolean,
        val resourceContent: String?
    )

    data class YouTubeResult(
        val success: Boolean,
        val videos: Map<String, MediaOption> = emptyMap(),
        val error: String? = null
    )

    private val client = httpClientProvider.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val authMutex = Mutex()

    @Volatile
    private var authFetched = false

    @Volatile
    private var auth: String = FALLBACK_AUTH

    @Volatile
    private var domain: String = FALLBACK_DOMAIN

    /** Extracts available formats. Conversion of non-direct formats is deferred. */
    suspend fun extract(url: String): YouTubeResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Extracting YouTube video: $url")
        ensureAuthFetched()

        try {
            val videoData = parseVideo(url)
            val resources = videoData.optJSONArray("resources")
            if (resources == null || resources.length() == 0) {
                return@withContext YouTubeResult(success = false, error = "No video resources found")
            }

            val videos = linkedMapOf<String, MediaOption>()
            for (i in 0 until resources.length()) {
                val resource = resources.getJSONObject(i)
                val label = labelFor(resource)
                val size = resource.optLong("size", 0).takeIf { it > 0L }

                if (resource.optString("download_mode") == "direct") {
                    val downloadUrl = resource.optString("download_url")
                    if (downloadUrl.isNotEmpty()) {
                        videos[label] = MediaOption(downloadUrl, size, isDirect = true, resourceContent = null)
                    }
                } else {
                    val resourceContent = resource.optString("resource_content")
                    if (resourceContent.isNotEmpty()) {
                        videos[label] = MediaOption(url = "", sizeBytes = size, isDirect = false, resourceContent = resourceContent)
                    }
                }
            }

            if (videos.isEmpty()) {
                return@withContext YouTubeResult(success = false, error = "Failed to extract any video links")
            }

            Log.d(TAG, "Found ${videos.size} video/audio options")
            YouTubeResult(success = true, videos = videos)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube video", e)
            YouTubeResult(success = false, error = "Error: ${e.message}")
        }
    }

    /** Converts a previously extracted resource on demand and returns the download URL. */
    suspend fun convertVideo(resourceContent: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Converting video on-demand...")
        val taskId = requestDownload(resourceContent)
        Log.d(TAG, "Task ID: $taskId")
        monitorDownload(taskId)
    }

    private fun labelFor(resource: JSONObject): String {
        val quality = resource.optString("quality")
        return if (resource.optString("type") == "audio") MediaLabel.audio(quality) else quality
    }

    private suspend fun ensureAuthFetched() {
        if (authFetched) return
        authMutex.withLock {
            if (!authFetched) {
                fetchAuthFromSite()
                authFetched = true
            }
        }
    }

    /** Scrapes the auth token and API domain from the VidsSave site; falls back to known values. */
    private fun fetchAuthFromSite() {
        try {
            Log.d(TAG, "Fetching auth token from VidsSave...")

            val mainHtml = get("$VIDSSAVE_SITE")
            val chunkPath = CHUNK_URL_RE.find(mainHtml)?.value ?: FALLBACK_CHUNK_PATH
            val jsContent = get("$VIDSSAVE_SITE$chunkPath")

            AUTH_RE.find(jsContent)?.groupValues?.get(1)?.let {
                auth = it
                Log.d(TAG, "Auth token found")
            }

            (DOMAIN_RE.find(jsContent) ?: VIDEO_DOWNLOAD_DOMAIN_RE.find(jsContent))
                ?.groupValues?.get(1)
                ?.let {
                    domain = it
                    Log.d(TAG, "Domain found: $domain")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch auth from site, using fallbacks: ${e.message}")
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ExtractionException("Request failed: HTTP ${response.code}")
            }
            response.body.string()
        }
    }

    private fun parseVideo(videoUrl: String): JSONObject {
        val formBody = FormBody.Builder()
            .add("auth", auth)
            .add("domain", domain)
            .add("origin", "source")
            .add("link", videoUrl)
            .build()

        val json = post("$BASE_URL/media/parse", formBody)
        if (json.optInt("status") != 1) {
            throw Exception("Parse failed: ${json.optString("msg", "Unknown error")}")
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun requestDownload(resourceContent: String): String {
        val formBody = FormBody.Builder()
            .add("auth", auth)
            .add("domain", domain)
            .add("request", resourceContent)
            .add("no_encrypt", "1")
            .build()

        val json = post("$BASE_URL/media/download", formBody)
        if (json.optInt("status") != 1) {
            throw Exception("Download request failed: ${json.optString("msg", "Unknown error")}")
        }
        return json.optJSONObject("data")?.optString("task_id")
            ?: throw Exception("No task_id in response")
    }

    private fun post(url: String, formBody: FormBody): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .header("Referer", VIDSSAVE_SITE)
            .header("Origin", VIDSSAVE_SITE)
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ExtractionException("Request failed: HTTP ${response.code}")
            }
            response.body.string()
        }
        return JSONObject(body)
    }

    /** Follows the conversion task via SSE until it succeeds, fails, or times out. */
    private fun monitorDownload(taskId: String, timeoutSeconds: Int = 300): String {
        val url = SSE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("auth", auth)
            .addQueryParameter("domain", domain)
            .addQueryParameter("task_id", taskId)
            .addQueryParameter("download_domain", "vidssave.com")
            .addQueryParameter("origin", "content_site")
            .build()

        val request = Request.Builder()
            .url(url)
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

                for (line in reader.lineSequence()) {
                    if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) {
                        throw Exception("Download conversion timeout")
                    }

                    when {
                        line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                        line.startsWith("data:") -> {
                            val dataStr = line.substringAfter("data:").trim()
                            try {
                                val data = JSONObject(dataStr)
                                when (eventType) {
                                    "success" -> {
                                        val downloadLink = data.optString("download_link")
                                        if (downloadLink.isNotEmpty()) return downloadLink
                                    }
                                    "failed" -> throw Exception("Download conversion failed: $dataStr")
                                    "running" -> Log.d(TAG, "Progress: ${data.optInt("progress", 0)}%")
                                }
                            } catch (_: JSONException) {
                                // Non-JSON data lines are ignored.
                            }
                        }
                    }
                }
            }
        }

        throw Exception("SSE stream ended without success")
    }

    private companion object {
        const val TAG = "YouTubeExtractor"
        const val BASE_URL = "https://api.vidssave.com/api/contentsite_api"
        const val SSE_URL = "https://api.vidssave.com/sse/contentsite_api/media/download_query"
        const val VIDSSAVE_SITE = "https://vidssave.com"

        // Fallbacks used when scraping the site fails.
        const val FALLBACK_AUTH = "20250901majwlqo"
        const val FALLBACK_DOMAIN = "api-ak.vidssave.com"
        const val FALLBACK_CHUNK_PATH = "/_next/static/chunks/9864-ae165a64347d921f.js"

        val CHUNK_URL_RE = Regex("/_next/static/chunks/9864-[a-f0-9]+\\.js")
        val AUTH_RE = Regex("""auth:\s*["']([^"']+)["']""")
        val DOMAIN_RE = Regex("""domain:\s*["']([^"']+)["']""")
        val VIDEO_DOWNLOAD_DOMAIN_RE = Regex("""VIDEODOWNLOAD:\s*["']([^"']+)["']""")
    }
}
