package com.elewashy.nexa.feature.downloads.data.engine

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadMetadata
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DownloadHttpTest {
    private val item = DownloadItem(1, "https://example.test/file?sig=a%2Fb+c", "file", "/tmp/file",
        userAgent = "Browser UA", referer = "https://source.test/full/path?x=1",
        metadata = DownloadMetadata(headers = mapOf("Authorization" to "Bearer token", "Accept-Language" to "fr", "user-agent" to "Captured UA"), etag = "\"v1\""))

    private fun response(code: Int = 206, range: String = "bytes 10-19/100", etag: String = "\"v1\"", length: Int = 10): Response =
        Response.Builder().request(DownloadHttp.request(item, 10, 19)).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").header("Content-Range", range).header("ETag", etag)
            .body(ByteArray(length).toResponseBody()).build()

    @Test fun `request metadata is preserved without invented origin or language`() {
        val request = DownloadHttp.request(item, 10, 19)
        assertEquals(item.url, request.url.toString())
        assertEquals("Captured UA", request.header("User-Agent"))
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("fr", request.header("Accept-Language"))
        assertEquals(item.referer, request.header("Referer"))
        assertNull(request.header("Origin"))
        assertEquals("bytes=10-19", request.header("Range"))
        assertEquals("\"v1\"", request.header("If-Range"))
    }

    @Test fun `valid partial response accepted`() {
        response().use { DownloadHttp.validate(it, 10, 19, 100, "\"v1\"") }
    }

    @Test fun `invalid ranges lengths and validators are rejected before writes`() {
        val invalid = listOf(response(200), response(416), response(range = "bytes 0-9/100"),
            response(range = "bytes 10-20/100"), response(range = "bytes 10-19/101"),
            response(range = "bytes 10-19/*"), response(etag = "\"v2\""), response(length = 9))
        invalid.forEach { resp ->
            resp.use {
                assertThrows(UnsafeResumeException::class.java) { DownloadHttp.validate(it, 10, 19, 100, "\"v1\"") }
            }
        }
    }

    @Test fun `range parser rejects overflow malformed and impossible values`() {
        listOf("bytes -1-9/100", "bytes 10-9/100", "bytes 0-100/100", "bytes 0-9/0", "bytes 0-9/999999999999999999999", "garbage").forEach {
            assertNull(ContentRange.parse(it))
        }
    }

    @Test fun `POST is never silently converted into GET`() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadHttp.request(item.copy(metadata = DownloadMetadata(method = "POST")))
        }
    }

    @Test fun `cross origin resolved url cannot inherit credentials`() {
        val request = DownloadHttp.request(item.copy(cookies = "session=secret", metadata = item.metadata.copy(
            resolvedUrl = "https://other.test/file", headers = item.metadata.headers + ("Cookie" to "secret"))))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
    }

    @Test fun `last modified must be a strong validator relative to response date`() {
        val valid = response().newBuilder().header("Last-Modified", "Tue, 01 Oct 2024 10:00:00 GMT")
            .header("Date", "Tue, 01 Oct 2024 10:01:01 GMT").build()
        valid.use { assertEquals("Tue, 01 Oct 2024 10:00:00 GMT", DownloadHttp.strongLastModified(it)) }
        valid.newBuilder().header("Date", "Tue, 01 Oct 2024 10:00:30 GMT").build().use {
            assertNull(DownloadHttp.strongLastModified(it))
        }
    }

    @Test fun `weak etag cannot authorize resume`() {
        assertNull(DownloadMetadata(etag = "W/\"v1\"").validator)
    }
}
