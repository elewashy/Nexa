package com.elewashy.nexa.feature.browser.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionParserTest {

    // ── OpenSearch (Google, Bing, Brave, Startpage) ─────────────────

    @Test
    fun `openSearch parser removes exact query, blanks, and duplicates`() {
        val body = """["go",["go","Google","google","  ","google maps"]]"""
        assertEquals(
            listOf("Google", "google maps"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.OpenSearch, body, query = "go", limit = 8),
        )
    }

    @Test
    fun `openSearch parser respects the limit`() {
        val body = """["test",["one","two","three","four","five"]]"""
        assertEquals(
            listOf("one", "two", "three"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.OpenSearch, body, query = "test", limit = 3),
        )
    }

    @Test
    fun `openSearch parser throws on malformed body so the repository can degrade`() {
        assertTrue(runCatching { SearchSuggestionParser.parse(SearchSuggestionFormat.OpenSearch, "not-json", "go", 8) }.isFailure)
    }

    // ── DuckDuckGo ──────────────────────────────────────────────────

    @Test
    fun `duckDuckGo parser extracts phrases`() {
        val body = """[{"phrase":"test speed"},{"phrase":"test microphone"},{"phrase":"test"}]"""
        assertEquals(
            listOf("test speed", "test microphone"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.DuckDuckGo, body, query = "test", limit = 8),
        )
    }

    @Test
    fun `duckDuckGo parser skips missing phrase keys`() {
        val body = """[{"phrase":"hello"},{"other":"ignored"},{"phrase":"world"}]"""
        assertEquals(
            listOf("hello", "world"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.DuckDuckGo, body, query = "test", limit = 8),
        )
    }

    // ── Yahoo ───────────────────────────────────────────────────────

    @Test
    fun `yahoo parser extracts k values`() {
        val body = """{"q":"test","r":[{"k":"test book"},{"k":"test speed"},{"k":"test"}]}"""
        assertEquals(
            listOf("test book", "test speed"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.Yahoo, body, query = "test", limit = 8),
        )
    }

    @Test
    fun `yahoo parser returns empty for missing r array`() {
        val body = """{"q":"test"}"""
        assertEquals(
            emptyList<String>(),
            SearchSuggestionParser.parse(SearchSuggestionFormat.Yahoo, body, query = "test", limit = 8),
        )
    }

    // ── Ecosia ──────────────────────────────────────────────────────

    @Test
    fun `ecosia parser extracts suggestions array`() {
        val body = """{"query":"test","suggestions":["test speed","test","test microphone"]}"""
        assertEquals(
            listOf("test speed", "test microphone"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.Ecosia, body, query = "test", limit = 8),
        )
    }

    @Test
    fun `ecosia parser returns empty for missing suggestions`() {
        val body = """{"query":"test"}"""
        assertEquals(
            emptyList<String>(),
            SearchSuggestionParser.parse(SearchSuggestionFormat.Ecosia, body, query = "test", limit = 8),
        )
    }

    // ── Qwant ───────────────────────────────────────────────────────

    @Test
    fun `qwant parser extracts item values`() {
        val body = """{"status":"success","data":{"items":[{"value":"test","suggestType":0},{"value":"testbook","suggestType":0}]}}"""
        assertEquals(
            listOf("testbook"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.Qwant, body, query = "test", limit = 8),
        )
    }

    @Test
    fun `qwant parser returns empty for missing data`() {
        val body = """{"status":"success"}"""
        assertEquals(
            emptyList<String>(),
            SearchSuggestionParser.parse(SearchSuggestionFormat.Qwant, body, query = "test", limit = 8),
        )
    }

    // ── Cross-format ────────────────────────────────────────────────

    @Test
    fun `all parsers filter out the exact query case-insensitively`() {
        val query = "Test"
        val bodies = mapOf(
            SearchSuggestionFormat.OpenSearch to """["Test",["Test","test speed"]]""",
            SearchSuggestionFormat.DuckDuckGo to """[{"phrase":"Test"},{"phrase":"test speed"}]""",
            SearchSuggestionFormat.Yahoo to """{"r":[{"k":"Test"},{"k":"test speed"}]}""",
            SearchSuggestionFormat.Ecosia to """{"suggestions":["Test","test speed"]}""",
            SearchSuggestionFormat.Qwant to """{"data":{"items":[{"value":"Test"},{"value":"test speed"}]}}""",
        )
        assertEquals(SearchSuggestionFormat.entries.toSet(), bodies.keys)
        bodies.forEach { (format, body) ->
            val result = SearchSuggestionParser.parse(format, body, query, 8)
            assertEquals("$format should filter exact query", listOf("test speed"), result)
        }
    }

    @Test
    fun `duplicates do not consume the limit`() {
        val body = """["go",["Google","google","GOOGLE","go pro","golang"]]"""
        assertEquals(
            listOf("Google", "go pro"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.OpenSearch, body, query = "go", limit = 2),
        )
    }

    @Test
    fun `limit is bounded to MAX_RESULTS and at least one`() {
        val values = (1..20).joinToString(",") { "\"s$it\"" }
        val body = """["q",[$values]]"""
        assertEquals(
            SearchSuggestionParser.MAX_RESULTS,
            SearchSuggestionParser.parse(SearchSuggestionFormat.OpenSearch, body, "q", limit = 100).size,
        )
        assertEquals(
            listOf("s1"),
            SearchSuggestionParser.parse(SearchSuggestionFormat.OpenSearch, body, "q", limit = 0),
        )
    }
}
