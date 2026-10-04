package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.core.network.awaitResponse
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * YouTube extraction via the VidsSave API (the backend of vidssave.com).
 *
 * Protocol, as used by the site:
 *  - `POST media/parse` with `hostname`, `auth`, `domain`, `origin` and `link`.
 *    `origin=cache` answers in ~0.3 s with direct, ready-to-download links for
 *    videos the service has already processed (`not_result` otherwise);
 *    `origin=source` analyses the video (~0.4–1.5 s) and lists every format,
 *    most of which are converted on demand.
 *  - A successful response carries `data` as AES-CBC ciphertext (base64,
 *    zero padding, IV = the key's first 16 bytes).
 *  - `auth_failed` / `analyze_failed` mean the credentials were rejected.
 *
 * Both origins are queried in parallel. Cached direct links replace the
 * matching converted formats, so the user downloads without a conversion
 * wait, while formats only the source lists stay available. When the cache
 * answers, the source gets [sourceGraceMs] to finish before the cached
 * formats are shown on their own.
 *
 * The last known credentials are used first; only when the API rejects them
 * are fresh ones scraped from the site's scripts (rate-limited, and shared by
 * concurrent callers), so a typical extraction costs two parallel requests.
 * Non-direct formats are converted on demand via SSE only when the user
 * actually selects them.
 */
class YouTubeExtractor internal constructor(
    private val client: OkHttpClient,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sourceGraceMs: Long = SOURCE_GRACE_MS,
) {

    constructor(httpClientProvider: HttpClientProvider) : this(
        httpClientProvider.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    )

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

    /** API credentials and response keys, replaced as a whole so readers never see a mixed set. */
    internal data class Credentials(val auth: String, val domain: String, val keys: List<String>)

    /** One entry of `data.resources`, reduced to the fields extraction uses. */
    internal data class Resource(
        val type: String,
        val quality: String,
        val format: String,
        val sizeBytes: Long?,
        val directUrl: String?,
        val resourceContent: String?,
    ) {
        val label: String get() = if (type == TYPE_AUDIO) MediaLabel.audio(quality) else quality

        /** Direct when the API serves a link, else a conversion of [resourceContent]. */
        fun toOption(): MediaOption? = when {
            directUrl != null -> MediaOption(directUrl, sizeBytes, isDirect = true, resourceContent = null)
            !resourceContent.isNullOrEmpty() -> MediaOption("", sizeBytes, isDirect = false, resourceContent)
            else -> null
        }
    }

    /** Values found in one of the site's scripts; null when the script does not contain them. */
    internal data class ScrapedValues(val auth: String?, val domain: String?, val keys: List<String>)

    private sealed interface ParseOutcome {
        class Parsed(val resources: List<Resource>) : ParseOutcome
        class Failed(val error: Exception) : ParseOutcome
    }

    private val authMutex = Mutex()

    @Volatile
    private var credentials = DEFAULT_CREDENTIALS

    /** [nanoTime] of the last scrape; guarded by [authMutex]. */
    private var lastRefreshNanos: Long? = null

    /**
     * Extracts available formats. Conversion of non-direct formats is deferred.
     * Network failures and cancellation propagate to the caller.
     */
    suspend fun extract(url: String): YouTubeResult = withContext(Dispatchers.IO) {
        val (cache, source) = coroutineScope {
            val cached = async { parseOutcome(url, ORIGIN_CACHE) }
            val analysed = async { parseOutcome(url, ORIGIN_SOURCE) }
            val cache = cached.await()
            val source = if (cache is ParseOutcome.Parsed && cache.resources.any(::isFastResource)) {
                // The cached formats are ready; don't hold them back for a slow analysis.
                withTimeoutOrNull(sourceGraceMs) { analysed.await() }.also { if (it == null) analysed.cancel() }
            } else {
                analysed.await()
            }
            cache to source
        }

        val options = mergeOptions(
            cached = (cache as? ParseOutcome.Parsed)?.resources.orEmpty(),
            source = (source as? ParseOutcome.Parsed)?.resources.orEmpty(),
        )
        if (options.isNotEmpty()) {
            Log.d(TAG, "Found ${options.size} video/audio options")
            return@withContext YouTubeResult(success = true, videos = options)
        }
        when (val error = (source as? ParseOutcome.Failed)?.error) {
            null -> YouTubeResult(success = false, error = "No video resources found")
            is ApiException -> YouTubeResult(success = false, error = error.message)
            else -> throw error
        }
    }

    /**
     * Converts a previously extracted resource on demand and returns the
     * download URL. Cancellation stops the conversion's in-flight requests.
     */
    suspend fun convertVideo(resourceContent: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Converting video on-demand...")
        val (taskId, used) = withAuthRetry { credentials ->
            requestDownload(resourceContent, credentials) to credentials
        }
        Log.d(TAG, "Conversion task started")
        monitorDownload(taskId, used)
    }

    private suspend fun parseOutcome(url: String, origin: String): ParseOutcome = try {
        ParseOutcome.Parsed(withAuthRetry { credentials -> parseVideo(url, origin, credentials) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (origin == ORIGIN_SOURCE) Log.w(TAG, "Parse ($origin) failed: ${e.message}")
        ParseOutcome.Failed(e)
    }

    /**
     * Runs [request] with the current credentials; when the API rejects them,
     * refreshes the credentials and retries once with the new ones.
     */
    private suspend fun <T> withAuthRetry(request: suspend (Credentials) -> T): T {
        val used = credentials
        return try {
            request(used)
        } catch (e: CredentialsRejectedException) {
            if (!refreshAuth(rejected = used)) throw ApiException(e.message ?: "Credentials rejected")
            Log.d(TAG, "Retrying with refreshed credentials")
            try {
                request(credentials)
            } catch (retry: CredentialsRejectedException) {
                throw ApiException(retry.message ?: "Credentials rejected")
            }
        }
    }

    /**
     * Replaces the [rejected] credentials with freshly scraped ones. Returns
     * true when a retry may succeed: another caller already replaced them, or
     * the scrape produced different ones. Scrapes at most once per
     * [MIN_REFRESH_INTERVAL_NANOS].
     */
    private suspend fun refreshAuth(rejected: Credentials): Boolean = authMutex.withLock {
        if (credentials !== rejected) return@withLock true
        val now = nanoTime()
        lastRefreshNanos?.let { if (now - it < MIN_REFRESH_INTERVAL_NANOS) return@withLock false }
        lastRefreshNanos = now
        val scraped = scrapeCredentials(current = rejected)
        if (scraped == null || scraped == rejected) return@withLock false
        credentials = scraped
        true
    }

    /**
     * Reads the auth token, API domain and response keys from the site's
     * scripts. Scripts are searched a few at a time and the search stops at
     * the first one that holds the auth token. Values the scripts do not
     * reveal keep their [current] ones; null when no auth token was found.
     */
    private suspend fun scrapeCredentials(current: Credentials): Credentials? {
        Log.d(TAG, "Fetching credentials from VidsSave...")
        val html = try {
            get(VIDSSAVE_SITE)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the site: ${e.message}")
            return null
        } catch (e: ExtractionException) {
            Log.w(TAG, "Could not read the site: ${e.message}")
            return null
        }
        var domain: String? = null
        for (batch in CHUNK_PATH_RE.findAll(html).map { it.value }.distinct().chunked(SCRAPE_PARALLELISM)) {
            val found = coroutineScope { batch.map { path -> async { scrapeScript(path) } }.awaitAll() }
            domain = domain ?: found.firstNotNullOfOrNull { it?.domain }
            val withAuth = found.firstOrNull { it?.auth != null } ?: continue
            return Credentials(
                auth = withAuth.auth!!,
                domain = domain ?: current.domain,
                keys = withAuth.keys.ifEmpty { current.keys },
            )
        }
        Log.w(TAG, "No credentials found in the site's scripts")
        return null
    }

    private suspend fun scrapeScript(path: String): ScrapedValues? = try {
        scrapeValues(get("$VIDSSAVE_SITE$path"))
    } catch (e: IOException) {
        null
    } catch (e: ExtractionException) {
        null
    }

    private suspend fun parseVideo(videoUrl: String, origin: String, credentials: Credentials): List<Resource> {
        val data = post("media/parse", credentials, "origin" to origin, "link" to videoUrl)
        return parseResources(data.optJSONArray("resources"))
    }

    private suspend fun requestDownload(resourceContent: String, credentials: Credentials): String =
        post("media/download", credentials, "request" to resourceContent, "no_encrypt" to "1")
            .optString("task_id").takeIf { it.isNotEmpty() }
            ?: throw ExtractionException("No task_id in response")

    /** Calls an API endpoint and returns its decoded `data`. */
    private suspend fun post(path: String, credentials: Credentials, vararg fields: Pair<String, String>): JSONObject {
        val form = FormBody.Builder()
            .add("hostname", HOSTNAME)
            .add("auth", credentials.auth)
            .add("domain", credentials.domain)
            .apply { fields.forEach { (name, value) -> add(name, value) } }
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/$path")
            .post(form)
            .header("Referer", "$VIDSSAVE_SITE/")
            .header("Origin", VIDSSAVE_SITE)
            .build()

        val body = executeForBody(request) { response ->
            if (response.code == 401 || response.code == 403) {
                throw CredentialsRejectedException("Request rejected: HTTP ${response.code}")
            }
        }
        return decodeResponse(body, credentials.keys)
    }

    private suspend fun get(url: String): String = executeForBody(Request.Builder().url(url).build())

    /** Executes an API-sized request with a hard [API_CALL_TIMEOUT_SECONDS] cap. */
    private suspend inline fun executeForBody(request: Request, check: (Response) -> Unit = {}): String {
        val call = client.newCall(request)
        call.timeout().timeout(API_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return call.awaitResponse().use { response ->
            check(response)
            if (!response.isSuccessful) {
                throw ExtractionException("Request failed: HTTP ${response.code}", ExtractionError.NETWORK)
            }
            response.body.string()
        }
    }

    /**
     * Follows the conversion task via SSE until it succeeds, fails, or
     * [CONVERSION_TIMEOUT_MS] elapses. The stream is read on a child
     * coroutine so that cancelling the caller cancels the call, which also
     * unblocks the read.
     */
    private suspend fun monitorDownload(taskId: String, credentials: Credentials): String {
        val url = SSE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("auth", credentials.auth)
            .addQueryParameter("domain", credentials.domain)
            .addQueryParameter("task_id", taskId)
            .addQueryParameter("download_domain", "vidssave.com")
            .addQueryParameter("origin", "content_site")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Referer", "$VIDSSAVE_SITE/")
            .header("Origin", VIDSSAVE_SITE)
            .build()

        // withTimeoutOrNull, not withTimeout: a timeout must surface as a failure, not as a cancellation.
        return withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
            val call = client.newCall(request)
            call.awaitResponse().use { response ->
                coroutineScope {
                    val reading = async { readConversionResult(response) }
                    try {
                        reading.await()
                    } catch (e: CancellationException) {
                        call.cancel()
                        throw e
                    }
                }
            }
        } ?: throw ExtractionException("Download conversion timed out", ExtractionError.NETWORK)
    }

    /** Reads the SSE stream of a conversion task until its download link or its failure. */
    private fun readConversionResult(response: Response): String {
        if (!response.isSuccessful) {
            throw ExtractionException("SSE request failed: HTTP ${response.code}", ExtractionError.NETWORK)
        }
        var eventType = ""
        response.body.charStream().buffered().useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> {
                        val data = try {
                            JSONObject(line.substringAfter("data:").trim())
                        } catch (_: JSONException) {
                            continue // Non-JSON data lines are ignored.
                        }
                        when (eventType) {
                            "success" -> data.optString("download_link").takeIf { it.isNotEmpty() }?.let { return it }
                            "failed" -> throw ExtractionException("Download conversion failed: $data")
                            "running" -> Log.d(TAG, "Progress: ${data.optInt("progress", 0)}%")
                        }
                    }
                }
            }
        }
        throw ExtractionException("SSE stream ended without success", ExtractionError.NETWORK)
    }

    /** The API rejected the credentials, or its responses can no longer be decrypted; fresh ones may help. */
    private class CredentialsRejectedException(message: String) : Exception(message)

    /** The API declined the request for another reason (unavailable video, not cached, …). */
    private class ApiException(message: String) : Exception(message)

    internal companion object {
        private const val TAG = "YouTubeExtractor"
        private const val BASE_URL = "https://api.vidssave.com/api/contentsite_api"
        private const val SSE_URL = "https://api.vidssave.com/sse/contentsite_api/media/download_query"
        private const val VIDSSAVE_SITE = "https://vidssave.com"
        private const val HOSTNAME = "vidssave.com"
        private const val ORIGIN_CACHE = "cache"
        private const val ORIGIN_SOURCE = "source"
        private const val TYPE_AUDIO = "audio"

        private const val SOURCE_GRACE_MS = 1_500L
        private const val API_CALL_TIMEOUT_SECONDS = 30L
        private const val CONVERSION_TIMEOUT_MS = 300_000L
        private const val SCRAPE_PARALLELISM = 4
        private val MIN_REFRESH_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10)

        /** Status codes meaning the credentials were rejected (the latter for retired tokens). */
        private val CREDENTIALS_REJECTED = setOf("auth_failed", "analyze_failed")

        /** Values the site currently ships; refreshed from its scripts when the API rejects them. */
        private val DEFAULT_CREDENTIALS = Credentials(
            auth = "4c9b7d21",
            domain = "api-ak.vidssave.com",
            keys = listOf("4c9b7d2e4c9b7d2e4c9b7d2e4c9b7d21", "rz18efAXUbdiaO7k"),
        )

        private val CHUNK_PATH_RE = Regex("""/_next/static/chunks/[A-Za-z0-9_./%()\[\]-]+\.js""")
        private val AUTH_RE = Regex("""\bauth:\s*"([^"\\]+)"""")
        private val DOMAIN_RE = Regex("""VIDEODOWNLOAD:\s*"([^"\\]+)"""")

        /** The response-key array, which sits right before the key-length check in the site's crypto helper. */
        private val KEY_ARRAY_RE = Regex(
            """\[((?:"[A-Za-z0-9]*"(?:\.(?:repeat\(\d+\)|concat\("[A-Za-z0-9]*"\)))*,?)+)]\s*,\s*\w+\s*=\s*\w+\s*=>\s*\{\s*if\s*\(\s*!\s*\[\s*16\s*,\s*24\s*,\s*32\s*]"""
        )
        private val KEY_EXPRESSION_RE = Regex(""""([A-Za-z0-9]*)"((?:\.(?:repeat\(\d+\)|concat\("[A-Za-z0-9]*"\)))*)""")
        private val KEY_OPERATION_RE = Regex("""\.(?:repeat\((\d+)\)|concat\("([A-Za-z0-9]*)"\))""")
        private val AES_KEY_LENGTHS = setOf(16, 24, 32)

        /** Cached formats that can replace a conversion: muxed MP4 video and MP3 audio, as saved by the share sheet. */
        internal fun isFastResource(resource: Resource): Boolean = resource.directUrl != null &&
            if (resource.type == TYPE_AUDIO) resource.format.equals("mp3", ignoreCase = true)
            else resource.format.equals("mp4", ignoreCase = true)

        /**
         * Source formats in API order, with each cached direct format
         * replacing the source format of the same label (or added when the
         * source does not list it).
         */
        internal fun mergeOptions(cached: List<Resource>, source: List<Resource>): Map<String, MediaOption> {
            val options = linkedMapOf<String, MediaOption>()
            source.forEach { resource -> resource.toOption()?.let { options[resource.label] = it } }
            cached.filter(::isFastResource).forEach { resource -> resource.toOption()?.let { options[resource.label] = it } }
            return options
        }

        internal fun parseResources(resources: JSONArray?): List<Resource> {
            resources ?: return emptyList()
            return (0 until resources.length()).mapNotNull { index ->
                val resource = resources.optJSONObject(index) ?: return@mapNotNull null
                val downloadUrl = resource.optString("download_url")
                Resource(
                    type = resource.optString("type"),
                    quality = resource.optString("quality"),
                    format = resource.optString("format"),
                    sizeBytes = resource.optLong("size", 0).takeIf { it > 0L },
                    directUrl = downloadUrl.takeIf { resource.optString("download_mode") == "direct" && it.isNotEmpty() },
                    resourceContent = resource.optString("resource_content").ifEmpty { null },
                )
            }
        }

        /**
         * Returns the `data` of an API response, decrypting it when it is
         * ciphertext. Throws [CredentialsRejectedException] for rejected
         * credentials and for data none of [keys] decrypts, [ApiException]
         * for other API errors.
         */
        internal fun decodeResponse(body: String, keys: List<String>): JSONObject {
            val root = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw ExtractionException("Unexpected response", ExtractionError.NETWORK)
            }
            if (root.optInt("status") != 1) {
                val code = root.optString("status_code")
                val message = "${root.optString("msg", "Unknown error")} ($code)"
                if (code in CREDENTIALS_REJECTED) throw CredentialsRejectedException(message)
                throw ApiException(message)
            }
            val data = root.opt("data")
            return when {
                data is JSONObject -> data
                data is String -> data.trim().let { text ->
                    if (text.startsWith("{")) JSONObject(text) else decrypt(text, keys)
                }
                else -> JSONObject()
            }
        }

        private fun decrypt(ciphertext: String, keys: List<String>): JSONObject {
            val bytes = try {
                Base64.getDecoder().decode(ciphertext)
            } catch (e: IllegalArgumentException) {
                throw ExtractionException("Unexpected response", ExtractionError.NETWORK)
            }
            for (key in keys) {
                val keyBytes = key.toByteArray(Charsets.UTF_8)
                if (keyBytes.size !in AES_KEY_LENGTHS) continue
                try {
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes, 0, 16))
                    val plain = cipher.doFinal(bytes)
                    var length = plain.size
                    while (length > 0 && plain[length - 1] == 0.toByte()) length--
                    return JSONObject(String(plain, 0, length, Charsets.UTF_8))
                } catch (_: GeneralSecurityException) {
                    // Wrong key or block size; try the next key.
                } catch (_: JSONException) {
                    // Decrypted to garbage: wrong key.
                }
            }
            throw CredentialsRejectedException("Response could not be decrypted")
        }

        /** Auth token, API domain and response keys in one of the site's scripts. */
        internal fun scrapeValues(script: String): ScrapedValues = ScrapedValues(
            auth = AUTH_RE.find(script)?.groupValues?.get(1),
            domain = DOMAIN_RE.find(script)?.groupValues?.get(1),
            keys = KEY_ARRAY_RE.find(script)?.groupValues?.get(1)?.let(::evaluateKeys).orEmpty(),
        )

        /** Evaluates `"a".repeat(3).concat("b"),"c"` style key literals; keeps valid AES key lengths. */
        private fun evaluateKeys(array: String): List<String> = KEY_EXPRESSION_RE.findAll(array).map { expression ->
            var value = expression.groupValues[1]
            KEY_OPERATION_RE.findAll(expression.groupValues[2]).forEach { operation ->
                value = operation.groupValues[1].toIntOrNull()?.let { value.repeat(it.coerceAtMost(8)) }
                    ?: (value + operation.groupValues[2])
            }
            value
        }.filter { it.length in AES_KEY_LENGTHS }.toList()
    }
}
