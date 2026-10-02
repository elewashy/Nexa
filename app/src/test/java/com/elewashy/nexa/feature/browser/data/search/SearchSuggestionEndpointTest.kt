package com.elewashy.nexa.feature.browser.data.search

import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionEndpointTest {

    @Test
    fun `suggestion url appends the encoded query`() {
        val url = SearchEngine.DuckDuckGo.suggestionEndpoint.url("hello world & more")
        assertEquals("https://duckduckgo.com/ac/?q=hello%20world%20%26%20more", url.toString())
    }

    @Test
    fun `suggestion url keeps fixed parameters`() {
        val url = SearchEngine.Google.suggestionEndpoint.url("go")
        assertEquals("firefox", url.queryParameter("client"))
        assertEquals("go", url.queryParameter("q"))
    }

    @Test
    fun `every engine has an https endpoint carrying the query`() {
        SearchEngine.entries.forEach { engine ->
            val url = engine.suggestionEndpoint.url("test")
            assertTrue("${engine.name} must use HTTPS", url.isHttps)
            assertTrue("${engine.name} must carry the query", (0 until url.querySize).any { url.queryParameterValue(it) == "test" })
        }
    }
}
