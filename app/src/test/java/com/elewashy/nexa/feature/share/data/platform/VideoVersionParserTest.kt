package com.elewashy.nexa.feature.share.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [parseVideoVersions]. Requires org.json on the test
 * classpath (provided via testImplementation, android.jar does not ship it).
 */
class VideoVersionParserTest {

    @Test
    fun `valid array body with escaped url is parsed`() {
        val body = "{\"url\":\"https:\\/\\/cdn.example.com\\/video.mp4?a=1\\u0026b=2\",\"width\":720,\"height\":1280}"

        val versions = parseVideoVersions(body)

        assertEquals(1, versions.size)
        assertEquals("https://cdn.example.com/video.mp4?a=1&b=2", versions[0].url)
        assertEquals(720, versions[0].width)
        assertEquals(1280, versions[0].height)
    }

    @Test
    fun `multiple entries are parsed in order`() {
        val body =
            "{\"url\":\"https:\\/\\/cdn.example.com\\/hd.mp4\",\"width\":1080,\"height\":1920}," +
                "{\"url\":\"https:\\u002F\\u002Fcdn.example.com\\u002Fsd.mp4\",\"width\":480,\"height\":854}"

        val versions = parseVideoVersions(body)

        assertEquals(2, versions.size)
        assertEquals("https://cdn.example.com/hd.mp4", versions[0].url)
        assertEquals("https://cdn.example.com/sd.mp4", versions[1].url)
        assertEquals(1080, versions[0].width)
        assertEquals(480, versions[1].width)
    }

    @Test
    fun `entries with blank urls are skipped`() {
        val body =
            "{\"url\":\"\",\"width\":100,\"height\":100}," +
                "{\"width\":100,\"height\":100}," +
                "{\"url\":\"https://cdn.example.com/ok.mp4\",\"width\":720,\"height\":1280}"

        val versions = parseVideoVersions(body)

        assertEquals(1, versions.size)
        assertEquals("https://cdn.example.com/ok.mp4", versions[0].url)
    }

    @Test
    fun `missing dimensions default to zero`() {
        val body = "{\"url\":\"https://cdn.example.com/v.mp4\"}"

        val versions = parseVideoVersions(body)

        assertEquals(1, versions.size)
        assertEquals(0, versions[0].width)
        assertEquals(0, versions[0].height)
    }

    @Test
    fun `malformed body returns empty list instead of throwing`() {
        assertTrue(parseVideoVersions("not json at all").isEmpty())
        assertTrue(parseVideoVersions("{\"url\":\"https://cdn.example.com/v.mp4\"").isEmpty()) // truncated
        assertTrue(parseVideoVersions("[{]}").isEmpty())
        assertTrue(parseVideoVersions("").isEmpty())
    }
}
