package com.elewashy.nexa.feature.browser.data.resources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.network.HttpClientProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Update-pipeline guarantees: conditional requests, validation before
 * replacement, last-known-good on failure, mirrors, interval and backoff.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserResourceRepositoryTest {

    private lateinit var server: HttpServer
    private val requests = AtomicInteger()

    /** `If-None-Match` of the last request (null when absent). */
    @Volatile
    private var lastIfNoneMatch: String? = null

    /** Per-path behaviour: status code, body, ETag. */
    private class Route(var status: Int, var body: String, var etag: String? = null)

    private val routes = mutableMapOf<String, Route>()
    private val id = BrowserResourceId.EasyList
    private val validator: (File) -> Boolean = { !it.readText().startsWith("<") && it.length() > 0 }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val route = routes[exchange.requestURI.path]
            if (route == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            lastIfNoneMatch = ifNoneMatch
            if (route.etag != null && ifNoneMatch == route.etag) {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
                return@createContext
            }
            route.etag?.let { exchange.responseHeaders.add("ETag", it) }
            val bytes = route.body.toByteArray()
            exchange.sendResponseHeaders(route.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun repository(vararg paths: String): BrowserResourceRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return BrowserResourceRepository(context, HttpClientProvider()) { paths.map(::url) }
    }

    @Test
    fun `downloads, then revalidates with ETag without rewriting`() {
        routes["/list"] = Route(200, "||ads.example^\n", etag = "\"v1\"")
        val repo = repository("/list")

        val first = repo.refresh(id, intervalMs = DAY, force = false, validate = validator)
        assertTrue(first.checked && first.updated && !first.failed && first.available)
        assertEquals("||ads.example^\n", repo.fileFor(id).readText())

        val modified = repo.fileFor(id).lastModified()
        val second = repo.refresh(id, intervalMs = DAY, force = true, validate = validator)
        assertTrue(second.checked && !second.updated && !second.failed)
        assertEquals(modified, repo.fileFor(id).lastModified())
    }

    @Test
    fun `a full response without validators drops the stale ones`() {
        routes["/list"] = Route(200, "||ads.example^\n", etag = "\"v1\"")
        val repo = repository("/list")
        repo.refresh(id, intervalMs = DAY, force = false, validate = validator)

        // New content served without an ETag (e.g. by another mirror).
        routes["/list"] = Route(200, "||ads.example^\n||more.example^\n", etag = null)
        val second = repo.refresh(id, intervalMs = DAY, force = true, validate = validator)
        assertEquals("\"v1\"", lastIfNoneMatch)
        assertTrue(second.updated)

        repo.refresh(id, intervalMs = DAY, force = true, validate = validator)
        assertEquals("the old ETag must not be sent again", null, lastIfNoneMatch)
    }

    @Test
    fun `unchanged body is not reported as an update`() {
        routes["/list"] = Route(200, "||ads.example^\n")
        val repo = repository("/list")
        assertTrue(repo.refresh(id, DAY, force = true, validate = validator).updated)
        assertFalse(repo.refresh(id, DAY, force = true, validate = validator).updated)
    }

    @Test
    fun `invalid content keeps the last known good copy`() {
        routes["/list"] = Route(200, "||ads.example^\n")
        val repo = repository("/list")
        repo.refresh(id, DAY, force = true, validate = validator)

        routes["/list"] = Route(200, "<html>captive portal</html>")
        val result = repo.refresh(id, DAY, force = true, validate = validator)
        assertTrue(result.failed)
        assertFalse(result.updated)
        assertTrue(result.available)
        assertEquals("||ads.example^\n", repo.fileFor(id).readText())
        assertFalse(File(repo.fileFor(id).parentFile, repo.fileFor(id).name + ".tmp").exists())
    }

    @Test
    fun `server errors keep the cache and back off`() {
        routes["/list"] = Route(200, "||ads.example^\n")
        val repo = repository("/list")
        repo.refresh(id, DAY, force = true, validate = validator)

        routes["/list"] = Route(500, "")
        val result = repo.refresh(id, DAY, force = true, validate = validator)
        assertTrue(result.failed)
        assertEquals("||ads.example^\n", repo.fileFor(id).readText())
        // Within the backoff window the resource is not due, even with a tiny interval.
        assertFalse(repo.isDue(id, intervalMs = 1L))
    }

    @Test
    fun `falls back to mirrors when the primary fails`() {
        routes["/mirror"] = Route(200, "||tracker.example^\n")
        val repo = repository("/primary-missing", "/mirror")
        val result = repo.refresh(id, DAY, force = false, validate = validator)
        assertTrue(result.updated && !result.failed)
        assertEquals("||tracker.example^\n", repo.fileFor(id).readText())
    }

    @Test
    fun `respects the interval and manual mode`() {
        routes["/list"] = Route(200, "||ads.example^\n")
        val repo = repository("/list")
        // Missing cache: due even in manual mode (bootstrap).
        assertTrue(repo.isDue(id, intervalMs = null))
        repo.refresh(id, DAY, force = false, validate = validator)

        val checkedAt = repo.lastCheckedAt(id)
        assertFalse(repo.isDue(id, DAY, now = checkedAt + DAY - 1))
        assertTrue(repo.isDue(id, DAY, now = checkedAt + DAY))
        assertFalse(repo.isDue(id, intervalMs = null, now = checkedAt + 365 * DAY))

        val before = requests.get()
        val skipped = repo.refresh(id, DAY, force = false, validate = validator)
        assertFalse(skipped.checked)
        assertEquals("no request when not due", before, requests.get())
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
