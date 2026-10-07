package com.elewashy.nexa.feature.browser.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePageTest {

    @Test
    fun `scheme-less addresses become https`() {
        assertEquals("https://example.com/", HomePageUrls.normalize("example.com"))
        assertEquals("https://example.com/news?x=1#top", HomePageUrls.normalize("  example.com/news?x=1#top "))
        assertEquals("https://localhost:8080/", HomePageUrls.normalize("localhost:8080"))
        assertEquals("https://example.com:8443/app", HomePageUrls.normalize("example.com:8443/app"))
    }

    @Test
    fun `http and https addresses are kept and the host is lowercased`() {
        assertEquals("http://example.com/", HomePageUrls.normalize("http://example.com"))
        assertEquals("https://news.example.org/Path", HomePageUrls.normalize("HTTPS://News.Example.ORG/Path"))
    }

    @Test
    fun `anything that is not a web address is rejected`() {
        listOf(
            "",
            "   ",
            "javascript:alert(1)",
            "file:///sdcard/page.html",
            "data:text/html,hi",
            "intent://scan#Intent;end",
            "ftp://example.com/",
            "just words",
            "word",
            "https://",
            "https://user:pass@example.com/",
            "https://..example.com/",
            "https://example.com/" + "a".repeat(2100),
        ).forEach { input -> assertNull(input, HomePageUrls.normalize(input)) }
    }

    @Test
    fun `stored value selects the home page`() {
        assertEquals(HomePage.SearchEngineHome, HomePage.fromStoredValue(null))
        assertEquals(HomePage.SearchEngineHome, HomePage.fromStoredValue("javascript:alert(1)"))
        assertEquals(HomePage.Custom("https://example.com/"), HomePage.fromStoredValue("https://example.com/"))
    }

    @Test
    fun `search engine home follows the selected engine`() {
        assertEquals(SearchEngine.DuckDuckGo.homeUrl, HomePage.SearchEngineHome.urlFor(SearchEngine.DuckDuckGo))
        assertEquals("https://example.com/", HomePage.Custom("https://example.com/").urlFor(SearchEngine.Bing))
    }
}
