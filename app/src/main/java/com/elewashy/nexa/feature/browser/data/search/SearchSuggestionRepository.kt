package com.elewashy.nexa.feature.browser.data.search

interface SearchSuggestionRepository {
    /** Returns an empty list for network, protocol, and parse failures. */
    suspend fun suggestions(query: String, limit: Int = 8): List<String>
}
