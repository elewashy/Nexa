package com.elewashy.nexa.feature.share.data.platform

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlStreamScannerTest {

    private fun script(body: String) = """<script type="application/json"  data-content-len="9" data-sjs>$body</script>"""

    @Test
    fun `returns the first json script containing the needle that the transform accepts`() {
        val source = Buffer().writeUtf8(
            "<html>" + script("""{"a":1}""") + "<p>text</p>" + script("""{"code":"X","skip":true}""") +
                script("""{"code":"X","n":2}""") + "</html>"
        )

        val found = HtmlStreamScanner.firstJsonScript(source, needle = "\"code\":\"X\"") { body ->
            body.takeUnless { it.contains("skip") }
        }

        assertEquals("""{"code":"X","n":2}""", found)
    }

    @Test
    fun `stops reading right after the match`() {
        val tail = "<p>" + "x".repeat(500_000) + "</p>"
        val source = Buffer().writeUtf8("<head>" + script("""{"code":"X"}""") + tail)

        HtmlStreamScanner.firstJsonScript(source, needle = "\"code\":\"X\"") { it }

        // Only the matched block was consumed; its closing tag and everything after it are untouched.
        assertEquals("the rest of the page is never read", ("</script>" + tail).length.toLong(), source.size)
    }

    @Test
    fun `finds matches that straddle buffer segments far into the document`() {
        // Larger than okio's 8 KiB segments, so the pattern can cross a segment boundary.
        val prefix = "y".repeat(8_192 * 3 - 10)
        val source = Buffer().writeUtf8(prefix + script("""{"code":"X"}"""))

        assertEquals("""{"code":"X"}""", HtmlStreamScanner.firstJsonScript(source, "\"code\":\"X\"") { it })
    }

    @Test
    fun `yields null without a match or for an unterminated script`() {
        assertNull(HtmlStreamScanner.firstJsonScript(Buffer().writeUtf8(script("""{"a":1}""")), "\"code\"") { it })
        assertNull(HtmlStreamScanner.firstJsonScript(Buffer().writeUtf8("""<script type="application/json">{"code":1"""), "\"code\"") { it })
        assertNull(HtmlStreamScanner.firstJsonScript(Buffer(), "\"code\"") { it })
    }

    @Test
    fun `decodes the json string literal after a marker, honouring escapes`() {
        val js = """requireLazy([],function(){x({"isSidecar":true,"contextJSON":"{\"a\":\"q\\\"uote\",\"b\":\"\\\\\"}"});});"""

        assertEquals("""{"a":"q\"uote","b":"\\"}""", HtmlStreamScanner.jsonStringAfter(Buffer().writeUtf8(js), "\"contextJSON\":"))
    }

    @Test
    fun `json string after a missing marker or non-string value yields null`() {
        assertNull(HtmlStreamScanner.jsonStringAfter(Buffer().writeUtf8("""{"other":"x"}"""), "\"contextJSON\":"))
        assertNull(HtmlStreamScanner.jsonStringAfter(Buffer().writeUtf8("""{"contextJSON":null}"""), "\"contextJSON\":"))
        assertNull(HtmlStreamScanner.jsonStringAfter(Buffer().writeUtf8("""{"contextJSON":"unterminated"""), "\"contextJSON\":"))
    }
}
