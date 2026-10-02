package com.elewashy.nexa.feature.browser.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchEngineTest {

    @Test
    fun `default engine is Google`() {
        assertEquals(SearchEngine.Google, SearchEngine.DEFAULT)
    }

    @Test
    fun `fromStoredValue returns Google for unknown values`() {
        assertEquals(SearchEngine.Google, SearchEngine.fromStoredValue(-1))
        assertEquals(SearchEngine.Google, SearchEngine.fromStoredValue(999))
        assertEquals(SearchEngine.Google, SearchEngine.fromStoredValue(Int.MAX_VALUE))
    }

    @Test
    fun `fromStoredValue round-trips every engine`() {
        SearchEngine.entries.forEach { engine ->
            assertEquals(engine, SearchEngine.fromStoredValue(engine.storedValue))
        }
    }

    @Test
    fun `searchUrl percent-encodes the query`() {
        val url = SearchEngine.Google.searchUrl("hello world & kotlin")
        assertEquals("https://www.google.com/search?q=hello%20world%20%26%20kotlin", url)
    }

    @Test
    fun `every engine produces a valid https search url`() {
        SearchEngine.entries.forEach { engine ->
            val url = engine.searchUrl("test")
            assert(url.startsWith("https://")) { "${engine.name} search URL must be HTTPS" }
            assert(url.contains("test")) { "${engine.name} search URL must contain the query" }
        }
    }

    @Test
    fun `every engine produces a valid https home url`() {
        SearchEngine.entries.forEach { engine ->
            assert(engine.homeUrl.startsWith("https://")) { "${engine.name} home URL must be HTTPS" }
        }
    }

    @Test
    fun `searchPageQuery extracts query from Google result page`() {
        val url = "https://www.google.com/search?q=kotlin+android"
        assertEquals("kotlin android", SearchEngine.Google.searchPageQuery(url))
    }

    @Test
    fun `searchPageQuery extracts query from Google country TLD`() {
        val url = "https://www.google.co.uk/search?q=london+weather"
        assertEquals("london weather", SearchEngine.Google.searchPageQuery(url))
    }

    @Test
    fun `searchPageQuery returns null for non-search pages`() {
        assertNull(SearchEngine.Google.searchPageQuery("https://www.google.com/"))
        assertNull(SearchEngine.Google.searchPageQuery("https://www.google.com/maps"))
        assertNull(SearchEngine.Google.searchPageQuery("https://example.com/search?q=test"))
    }

    @Test
    fun `searchPageQuery extracts query from every engine's result page`() {
        val cases = mapOf(
            SearchEngine.Google to "https://www.google.com/search?q=test",
            SearchEngine.Bing to "https://www.bing.com/search?q=test",
            SearchEngine.DuckDuckGo to "https://duckduckgo.com/?q=test",
            SearchEngine.Yahoo to "https://search.yahoo.com/search?p=test",
            SearchEngine.Brave to "https://search.brave.com/search?q=test",
            SearchEngine.Startpage to "https://www.startpage.com/sp/search?query=test",
            SearchEngine.Ecosia to "https://www.ecosia.org/search?q=test",
            SearchEngine.Qwant to "https://www.qwant.com/?q=test",
        )
        cases.forEach { (engine, url) ->
            assertEquals("test", engine.searchPageQuery(url))
        }
    }

    @Test
    fun `searchQueryFromUrl finds the engine for any supported result page`() {
        assertEquals("test", SearchEngine.searchQueryFromUrl("https://www.google.com/search?q=test"))
        assertEquals("test", SearchEngine.searchQueryFromUrl("https://duckduckgo.com/?q=test"))
        assertEquals("test", SearchEngine.searchQueryFromUrl("https://www.qwant.com/?q=test"))
        assertNull(SearchEngine.searchQueryFromUrl("https://example.com/search?q=test"))
    }
}
