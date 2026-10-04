package com.elewashy.nexa.feature.share.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [YouTubeExtractor] against a fake VidsSave backend served by an
 * interceptor (no network). Responses are encrypted the way the API does it.
 */
class YouTubeExtractorTest {

    /** Written from OkHttp's threads. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var now = 0L

    /** The credentials the fake API accepts and the key it encrypts with. */
    private var validAuth = DEFAULT_AUTH
    private var responseKey = DEFAULT_KEY

    /** Credentials the fake site's scripts reveal. */
    private var siteAuth = "fresh"
    private var siteKey = "fresh5678fresh56"

    private var rejectionCode = "auth_failed"
    private var rejectWithHttp: Int? = null
    private var encrypt = true
    private var sourceDelayMs = 0L
    private var failSourceWith: IOException? = null

    private var cacheResources: JSONArray? = JSONArray()
        .put(resource("video", "720P", "MP4", direct = "https://cdn/c720.mp4"))
        .put(resource("audio", "128KBPS", "M4A", direct = "https://cdn/c128.m4a"))
    private var sourceResources = JSONArray()
        .put(resource("video", "720P", "MP4", content = "rc720"))
        .put(resource("video", "2160P", "WEBM", content = "rc2160"))
        .put(resource("audio", "128KBPS", "MP3", content = "rcA128"))

    private fun resource(type: String, quality: String, format: String, direct: String? = null, content: String? = null) =
        JSONObject()
            .put("type", type).put("quality", quality).put("format", format).put("size", "1000")
            .put("download_mode", if (direct != null) "direct" else "")
            .put("download_url", direct.orEmpty())
            .put("resource_content", content.orEmpty())

    private fun encrypted(data: JSONObject, key: String): String {
        val plain = data.toString().toByteArray()
        val padded = plain.copyOf((plain.size + 15) / 16 * 16)
        val keyBytes = key.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes, 0, 16))
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded))
    }

    private fun success(data: JSONObject): String {
        val payload: Any = if (encrypt) encrypted(data, responseKey) else data
        return JSONObject().put("status", 1).put("status_code", "success").put("data", payload).toString()
    }

    private fun failure(code: String, msg: String = "failed") =
        JSONObject().put("status", 0).put("status_code", code).put("msg", msg).toString()

    private fun Request.form(): Map<String, String> {
        val text = Buffer().also { body?.writeTo(it) }.readUtf8()
        return text.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    private fun respond(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun api(request: Request): Response {
        val form = request.form()
        val auth = form["auth"] ?: request.url.queryParameter("auth")
        val path = request.url.encodedPath
        val origin = form["origin"]
        requests += "${path.substringAfterLast('/')}${origin?.let { "($it)" }.orEmpty()} auth=$auth"
        if (path.endsWith("/download_query")) {
            return respond(request, 200, "event: running\ndata: {\"progress\":50}\n\nevent: success\ndata: {\"download_link\":\"https://cdn/converted.mp4\"}\n\n")
        }
        if (auth != validAuth) {
            return rejectWithHttp?.let { respond(request, it, "") } ?: respond(request, 200, failure(rejectionCode))
        }
        assertEquals("vidssave.com", form["hostname"])
        return when {
            path.endsWith("/media/parse") && origin == "cache" ->
                cacheResources?.let { respond(request, 200, success(JSONObject().put("resources", it))) }
                    ?: respond(request, 200, failure("not_result", "not found"))
            path.endsWith("/media/parse") -> {
                failSourceWith?.let { throw it }
                if (sourceDelayMs > 0) Thread.sleep(sourceDelayMs)
                respond(request, 200, success(JSONObject().put("title", "t").put("resources", sourceResources)))
            }
            path.endsWith("/media/download") -> respond(request, 200, success(JSONObject().put("task_id", "T1")))
            else -> respond(request, 404, "")
        }
    }

    private fun site(request: Request): Response {
        val path = request.url.encodedPath
        requests += "site $path"
        return when (path) {
            "/" -> respond(request, 200, """<script src="/_next/static/chunks/a-1.js"></script><script src="/_next/static/chunks/b-2.js"></script>""")
            "/_next/static/chunks/a-1.js" -> respond(request, 200, """r={VIDEODOWNLOAD:"api.example"},a={MAIN:1}""")
            "/_next/static/chunks/b-2.js" -> respond(
                request, 200,
                """let c=["${siteKey.take(8)}".repeat(1).concat("${siteKey.drop(8)}")],l=t=>{if(![16,24,32].includes(t.length))throw Error("AES key must be")};""" +
                    """y={hostname:"vidssave.com",auth:"$siteAuth",domain:m.bl.VIDEODOWNLOAD}""",
            )
            else -> respond(request, 404, "")
        }
    }

    private fun extractor(sourceGraceMs: Long = 5_000) = YouTubeExtractor(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.url.host == "vidssave.com") site(request) else api(request)
        }).build(),
        nanoTime = { now },
        sourceGraceMs = sourceGraceMs,
    )

    private fun YouTubeExtractor.YouTubeResult.summary() =
        videos.mapValues { (_, option) -> if (option.isDirect) option.url else "convert:${option.resourceContent}" }

    @Test
    fun `cached direct formats replace conversions and source-only formats are kept`() = runTest {
        val result = extractor().extract("https://youtu.be/abc")

        assertTrue(result.success)
        assertEquals(
            mapOf(
                "720P" to "https://cdn/c720.mp4",
                "2160P" to "convert:rc2160",
                // M4A would be saved as .mp3, so the MP3 conversion stays.
                "AUDIO:128KBPS" to "convert:rcA128",
            ),
            result.summary(),
        )
        assertEquals(1000L, result.videos.getValue("720P").sizeBytes)
    }

    @Test
    fun `a cache miss falls back to the source formats`() = runTest {
        cacheResources = null

        val result = extractor().extract("https://youtu.be/abc")

        assertEquals(mapOf("720P" to "convert:rc720", "2160P" to "convert:rc2160", "AUDIO:128KBPS" to "convert:rcA128"), result.summary())
    }

    @Test
    fun `cached formats are not held back by a slow analysis`() = runTest {
        sourceDelayMs = 3_000

        val started = System.nanoTime()
        val result = extractor(sourceGraceMs = 100).extract("https://youtu.be/abc")

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertEquals(mapOf("720P" to "https://cdn/c720.mp4"), result.summary())
    }

    @Test
    fun `responses with plain data are accepted`() = runTest {
        encrypt = false

        assertTrue(extractor().extract("https://youtu.be/abc").success)
    }

    @Test
    fun `an unavailable video fails without scraping the site`() = runTest {
        cacheResources = null
        validAuth = DEFAULT_AUTH
        sourceResources = JSONArray()
        val withoutResources = extractor().extract("https://youtu.be/abc")
        assertFalse(withoutResources.success)

        rejectionCode = "failed"
        validAuth = "nobody"
        val rejected = extractor().extract("https://youtu.be/abc")
        assertFalse(rejected.success)
        assertTrue(rejected.error!!.contains("failed"))
        assertTrue(requests.none { it.startsWith("site") })
    }

    @Test
    fun `a network failure of the analysis propagates when nothing is cached`() = runTest {
        cacheResources = null
        failSourceWith = IOException("offline")

        val failure = runCatching { extractor().extract("https://youtu.be/abc") }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun `rejected credentials are scraped once for both parallel requests and retried`() = runTest {
        validAuth = "fresh"
        responseKey = "fresh5678fresh56"

        val result = extractor().extract("https://youtu.be/abc")

        assertTrue(result.success)
        assertEquals(1, requests.count { it == "site /" })
        assertTrue("parse(cache) auth=fresh" in requests)
        assertTrue("parse(source) auth=fresh" in requests)
    }

    @Test
    fun `retired tokens, HTTP 403 and outdated keys also refresh the credentials`() = runTest {
        validAuth = "fresh"
        responseKey = "fresh5678fresh56"
        rejectionCode = "analyze_failed"
        assertTrue(extractor().extract("https://youtu.be/abc").success)

        rejectWithHttp = 403
        assertTrue(extractor().extract("https://youtu.be/abc").success)

        // Accepted credentials, but responses encrypted with a key the extractor does not know yet.
        validAuth = DEFAULT_AUTH
        siteAuth = DEFAULT_AUTH
        assertTrue(extractor().extract("https://youtu.be/abc").success)
    }

    @Test
    fun `scraping is rate limited when the API keeps rejecting`() = runTest {
        validAuth = "never"
        val extractor = extractor()
        assertFalse(extractor.extract("https://youtu.be/abc").success)
        requests.clear()

        now += TimeUnit.MINUTES.toNanos(9)
        assertFalse(extractor.extract("https://youtu.be/abc").success)
        assertTrue("no scrape within the interval", requests.none { it.startsWith("site") })

        now += TimeUnit.MINUTES.toNanos(1)
        assertFalse(extractor.extract("https://youtu.be/abc").success)
        assertEquals("scrapes again after the interval", 1, requests.count { it == "site /" })
    }

    @Test
    fun `conversion refreshes credentials and follows the task with the ones that started it`() = runTest {
        validAuth = "fresh"
        responseKey = "fresh5678fresh56"

        assertEquals("https://cdn/converted.mp4", extractor().convertVideo("rc720"))
        assertEquals("download_query auth=fresh", requests.last())
    }

    @Test
    fun `site scripts reveal the auth token, API domain and evaluated response keys`() {
        val script = """r={VIDEODOWNLOAD:"api-ak.vidssave.com"};let c=["4c9b7d2e".repeat(3).concat("4c9b7d21"),"rz18efAXUbdiaO7k","short"],""" +
            """l=t=>{if(![16,24,32].includes(t.length))throw Error("AES key must be 16, 24, or 32 bytes")};""" +
            """c={hostname:"vidssave.com",auth:"4c9b7d21",domain:m.bl.VIDEODOWNLOAD}"""

        assertEquals(
            YouTubeExtractor.ScrapedValues(
                auth = "4c9b7d21",
                domain = "api-ak.vidssave.com",
                keys = listOf("4c9b7d2e4c9b7d2e4c9b7d2e4c9b7d21", "rz18efAXUbdiaO7k"),
            ),
            YouTubeExtractor.scrapeValues(script),
        )
        val empty = YouTubeExtractor.scrapeValues("function(){return 1}")
        assertNull(empty.auth)
        assertTrue(empty.keys.isEmpty())
    }

    private companion object {
        const val DEFAULT_AUTH = "4c9b7d21"
        const val DEFAULT_KEY = "4c9b7d2e4c9b7d2e4c9b7d2e4c9b7d21"
    }
}
