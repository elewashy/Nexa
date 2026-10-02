package com.elewashy.nexa.feature.browser.data.search

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses autocomplete responses for every [SearchSuggestionFormat].
 *
 * Format-specific code only extracts raw candidates; filtering is shared:
 * blanks and the exact typed query are dropped, case-insensitive duplicates
 * are collapsed before the limit is applied, and at most `limit` (bounded by
 * [MAX_RESULTS]) results are returned. Malformed bodies throw
 * [org.json.JSONException], which the repository degrades to an empty list.
 */
internal object SearchSuggestionParser {
    const val MAX_RESULTS = 8

    fun parse(format: SearchSuggestionFormat, body: String, query: String, limit: Int): List<String> {
        val candidates = when (format) {
            SearchSuggestionFormat.OpenSearch -> JSONArray(body).optJSONArray(1).strings()
            SearchSuggestionFormat.DuckDuckGo -> JSONArray(body).objectStrings("phrase")
            SearchSuggestionFormat.Yahoo -> JSONObject(body).optJSONArray("r").objectStrings("k")
            SearchSuggestionFormat.Ecosia -> JSONObject(body).optJSONArray("suggestions").strings()
            SearchSuggestionFormat.Qwant ->
                JSONObject(body).optJSONObject("data")?.optJSONArray("items").objectStrings("value")
        }
        val seen = HashSet<String>()
        return candidates
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.equals(query, ignoreCase = true) && seen.add(it.lowercase()) }
            .take(limit.coerceIn(1, MAX_RESULTS))
            .toList()
    }

    private fun JSONArray?.strings(): Sequence<String> {
        if (this == null) return emptySequence()
        return (0 until length()).asSequence().map(::optString)
    }

    private fun JSONArray?.objectStrings(key: String): Sequence<String> {
        if (this == null) return emptySequence()
        return (0 until length()).asSequence().mapNotNull { optJSONObject(it)?.optString(key) }
    }
}
