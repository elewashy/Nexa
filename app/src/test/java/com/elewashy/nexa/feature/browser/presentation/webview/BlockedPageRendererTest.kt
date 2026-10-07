package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BlockedPageRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val renderer = BlockedPageRenderer(context)

    @Test
    fun `page names the blocked site and offers go back and proceed`() {
        val html = renderer.buildHtml(URL, FILTER, PROCEED)

        assertTrue(html.contains("<p class=\"host\" dir=\"ltr\">ads.example.com</p>"))
        assertTrue(html.contains("href=\"${WebViewContentBlocker.BACK_URL}\""))
        assertTrue(html.contains("href=\"nexa-adblock://proceed?token=t&amp;url=x\""))
        assertTrue(html.contains(context.getString(R.string.adblock_page_blocked_heading)))
        // Proceeding is a deliberate choice: it sits inside the collapsed Details section.
        assertTrue(html.indexOf("<details>") < html.indexOf(PROCEED.replace("&", "&amp;")))
    }

    @Test
    fun `untrusted values are escaped`() {
        val html = renderer.buildHtml(
            url = "https://evil.example/<script>alert(1)</script>",
            filter = "||evil.example^\"><img src=x>",
            proceedUrl = PROCEED,
        )

        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `filter row is omitted without a filter`() {
        val html = renderer.buildHtml(URL, filter = null, proceedUrl = PROCEED)
        assertFalse(html.contains(context.getString(R.string.adblock_page_blocked_filter)))
    }

    @Test
    @Config(qualifiers = "ar-ldrtl")
    fun `right-to-left languages render right to left`() {
        val html = BlockedPageRenderer(ApplicationProvider.getApplicationContext()).buildHtml(URL, FILTER, PROCEED)
        assertTrue(html.contains("dir=\"rtl\""))
    }

    @Test
    fun `response forbids scripts and is never cached`() {
        val response = renderer.render(URL, FILTER, PROCEED)

        assertEquals("text/html", response.mimeType)
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
        assertTrue(response.responseHeaders["Content-Security-Policy"]!!.contains("default-src 'none'"))
    }

    private companion object {
        const val URL = "https://ads.example.com/landing?id=1"
        const val FILTER = "||ads.example.com^\$document"
        const val PROCEED = "nexa-adblock://proceed?token=t&url=x"
    }
}
