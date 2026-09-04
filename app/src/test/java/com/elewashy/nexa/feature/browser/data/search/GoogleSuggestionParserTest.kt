package com.elewashy.nexa.feature.browser.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSuggestionParserTest {
    @Test
    fun `parser removes the exact query, blanks, and case-insensitive duplicates`() {
        val body = """["go",["go","Google","google","  ","google maps"]]"""

        assertEquals(
            listOf("Google", "google maps"),
            GoogleSuggestionParser.parse(body, query = "go", limit = 8),
        )
    }

    @Test
    fun `malformed response can be degraded by repository`() {
        assertTrue(runCatching { GoogleSuggestionParser.parse("not-json", "go", 8) }.isFailure)
    }
}
