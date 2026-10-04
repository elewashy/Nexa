package com.elewashy.nexa.feature.browser.presentation.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PageMediaProbeMessageTest {

    @Test
    fun `parses a report about the sending origin`() {
        assertEquals(
            "https://x.com/u/status/1" to true,
            PageMediaProbe.parse("""{"url":"https://x.com/u/status/1","hasMedia":true}""", "x.com"),
        )
    }

    @Test
    fun `rejects reports about another origin, malformed or oversized messages`() {
        assertNull(PageMediaProbe.parse("""{"url":"https://evil.com/u/status/1","hasMedia":true}""", "x.com"))
        assertNull(PageMediaProbe.parse("""{"url":"https://x.com/u/status/1"}""", "x.com"))
        assertNull(PageMediaProbe.parse("""{"url":"https://x.com/u/status/1","hasMedia":true}""", null))
        assertNull(PageMediaProbe.parse("not json", "x.com"))
        assertNull(PageMediaProbe.parse("""{"url":"ht tp://x","hasMedia":true}""", "x.com"))
        assertNull(PageMediaProbe.parse(null, "x.com"))
        assertNull(PageMediaProbe.parse("x".repeat(9000), "x.com"))
    }

    @Test
    fun `origin rules are https only and cover every probed platform`() {
        val rules = PageMediaProbe.ALLOWED_ORIGIN_RULES
        assertTrue(rules.all { it.startsWith("https://") })
        listOf("x.com", "twitter.com", "threads.net", "threads.com", "facebook.com").forEach { domain ->
            assertTrue(domain, "https://$domain" in rules && "https://*.$domain" in rules)
        }
    }

    @Test
    fun `script posts to the registered listener name`() {
        val script = Files.readString(Path.of("src/main/assets/media/page_media_probe.js"))
        assertTrue(script.contains("window.${PageMediaProbe.LISTENER_NAME}"))
    }
}
