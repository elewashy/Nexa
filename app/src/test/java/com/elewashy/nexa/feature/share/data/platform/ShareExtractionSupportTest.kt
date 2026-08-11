package com.elewashy.nexa.feature.share.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure companion helpers of [ShareExtractionSupport]:
 * URL unescaping, quality extraction, fallback heuristics and label/size
 * formatting.
 */
class ShareExtractionSupportTest {

    // ── decodeUrl ───────────────────────────────────────────────────────

    @Test
    fun `decodeUrl unescapes escaped slashes`() {
        assertEquals(
            "https://cdn.example.com/video.mp4",
            ShareExtractionSupport.decodeUrl("https:\\/\\/cdn.example.com\\/video.mp4")
        )
    }

    @Test
    fun `decodeUrl unescapes unicode ampersand`() {
        assertEquals(
            "https://cdn.example.com/v.mp4?a=1&b=2",
            ShareExtractionSupport.decodeUrl("https://cdn.example.com/v.mp4?a=1\\u0026b=2")
        )
    }

    @Test
    fun `decodeUrl unescapes unicode percent`() {
        assertEquals(
            "https://cdn.example.com/v.mp4?q=100%",
            ShareExtractionSupport.decodeUrl("https://cdn.example.com/v.mp4?q=100\\u0025")
        )
    }

    @Test
    fun `decodeUrl unescapes unicode slash`() {
        assertEquals(
            "https://cdn.example.com/video.mp4",
            ShareExtractionSupport.decodeUrl("https:\\u002F\\u002Fcdn.example.com\\u002Fvideo.mp4")
        )
    }

    @Test
    fun `decodeUrl does not double-decode percent sequences`() {
        // "\u00252F" is an escaped "%2F" — it must become "%2F", not "/".
        assertEquals("%2F", ShareExtractionSupport.decodeUrl("\\u00252F"))
    }

    @Test
    fun `decodeUrl leaves clean urls untouched`() {
        val url = "https://cdn.example.com/v.mp4?a=1&b=2"
        assertEquals(url, ShareExtractionSupport.decodeUrl(url))
    }

    // ── extractQuality ──────────────────────────────────────────────────

    @Test
    fun `extractQuality reads underscore quality mp4 pattern`() {
        assertEquals("720p", ShareExtractionSupport.extractQuality("https://cdn.example.com/video_720p.mp4"))
    }

    @Test
    fun `extractQuality reads dot quality pattern`() {
        assertEquals("1080p", ShareExtractionSupport.extractQuality("https://cdn.example.com/file.1080.mp4"))
        assertEquals("2160p", ShareExtractionSupport.extractQuality("https://cdn.example.com/file.2160.mp4"))
    }

    @Test
    fun `extractQuality reads width by height pattern`() {
        assertEquals("1280x720", ShareExtractionSupport.extractQuality("https://cdn.example.com/clip_1280x720.mp4"))
        assertEquals("640x360", ShareExtractionSupport.extractQuality("https://cdn.example.com/clip_640x360.mp4"))
    }

    @Test
    fun `extractQuality prefers the mp4 pattern over dot pattern`() {
        assertEquals(
            "540p",
            ShareExtractionSupport.extractQuality("https://cdn.example.com/file.1080._540p.mp4")
        )
    }

    @Test
    fun `extractQuality returns null when no pattern matches`() {
        assertNull(ShareExtractionSupport.extractQuality("https://cdn.example.com/video.mp4"))
    }

    // ── detectQuality ───────────────────────────────────────────────────

    @Test
    fun `detectQuality prefers explicit dimensions`() {
        assertEquals("720p", ShareExtractionSupport.detectQuality("https://cdn.example.com/v", width = 1280, height = 720))
        assertEquals("1080p", ShareExtractionSupport.detectQuality("https://cdn.example.com/v", width = 1920, height = 1080))
        assertEquals("480p", ShareExtractionSupport.detectQuality("https://cdn.example.com/v", width = 854, height = 480))
    }

    @Test
    fun `detectQuality falls back to url patterns without dimensions`() {
        assertEquals("720p", ShareExtractionSupport.detectQuality("https://cdn.example.com/video_720p.mp4"))
    }

    @Test
    fun `detectQuality uses 1280 and 1920 heuristics`() {
        assertEquals("720p", ShareExtractionSupport.detectQuality("https://cdn.example.com/streams/1280/playlist.m3u8"))
        assertEquals("1080p", ShareExtractionSupport.detectQuality("https://cdn.example.com/streams/1920/master.m3u8"))
    }

    @Test
    fun `detectQuality pattern match wins over resolution heuristics`() {
        assertEquals(
            "540p",
            ShareExtractionSupport.detectQuality("https://cdn.example.com/v_540p.mp4?width=1920")
        )
    }

    @Test
    fun `detectQuality falls back to generic video label`() {
        assertEquals("video", ShareExtractionSupport.detectQuality("https://cdn.example.com/watch/12345"))
    }

    // ── labelWithSize / formatBytes ─────────────────────────────────────

    @Test
    fun `labelWithSize leaves label alone when size is unknown or non-positive`() {
        assertEquals("720p", ShareExtractionSupport.labelWithSize("720p", null))
        assertEquals("720p", ShareExtractionSupport.labelWithSize("720p", 0L))
        assertEquals("720p", ShareExtractionSupport.labelWithSize("720p", -5L))
    }

    @Test
    fun `labelWithSize does not duplicate an existing MB suffix`() {
        assertEquals("720p - 12.3 MB", ShareExtractionSupport.labelWithSize("720p - 12.3 MB", 5_000_000L))
        assertEquals("720p 8.0 mb", ShareExtractionSupport.labelWithSize("720p 8.0 mb", 5_000_000L))
    }

    @Test
    fun `labelWithSize appends formatted size`() {
        assertEquals("720p - 10.0 MB", ShareExtractionSupport.labelWithSize("720p", 10L * 1024 * 1024))
    }

    @Test
    fun `formatBytes handles null and non-positive input`() {
        assertNull(ShareExtractionSupport.formatBytes(null))
        assertNull(ShareExtractionSupport.formatBytes(0L))
        assertNull(ShareExtractionSupport.formatBytes(-1L))
    }

    @Test
    fun `formatBytes formats megabytes with one decimal`() {
        assertEquals("1.0 MB", ShareExtractionSupport.formatBytes(1_048_576L))
        assertEquals("1.5 MB", ShareExtractionSupport.formatBytes(1_572_864L))
        assertEquals("10.0 MB", ShareExtractionSupport.formatBytes(10_485_760L))
        // Sub-megabyte values round down to 0.0 MB rather than failing.
        assertEquals("0.0 MB", ShareExtractionSupport.formatBytes(1024L))
    }
}
