package com.elewashy.nexa.feature.browser.data.search

import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Autocomplete response shape served by a search engine's suggestion endpoint. */
internal enum class SearchSuggestionFormat {
    /** OpenSearch JSON array: `["query", ["suggestion", …]]` (Google, Bing, Brave, Startpage). */
    OpenSearch,

    /** DuckDuckGo autocomplete: `[{"phrase": "…"}, …]`. */
    DuckDuckGo,

    /** Yahoo gossip endpoint: `{"r": [{"k": "…"}, …]}`. */
    Yahoo,

    /** Ecosia autocomplete: `{"query": "…", "suggestions": ["…"]}`. */
    Ecosia,

    /** Qwant v3 suggest: `{"data": {"items": [{"value": "…"}]}}`. */
    Qwant,
}

/**
 * Remote autocomplete contract for one search engine: the base URL (including
 * any fixed parameters), the parameter carrying the typed text, and the
 * response shape.
 */
internal class SearchSuggestionEndpoint(
    baseUrl: String,
    private val queryParameter: String,
    val format: SearchSuggestionFormat,
) {
    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    /** Request URL for [query]; OkHttp percent-encodes the parameter value. */
    fun url(query: String): HttpUrl = baseUrl.newBuilder()
        .addQueryParameter(queryParameter, query)
        .build()
}

/** Built once; the exhaustive `when` guarantees every engine has an endpoint. */
private val endpoints: Map<SearchEngine, SearchSuggestionEndpoint> by lazy {
    SearchEngine.entries.associateWith { engine ->
        when (engine) {
            SearchEngine.Google -> SearchSuggestionEndpoint(
                baseUrl = "https://suggestqueries.google.com/complete/search?client=firefox",
                queryParameter = "q",
                format = SearchSuggestionFormat.OpenSearch,
            )
            SearchEngine.Bing -> SearchSuggestionEndpoint(
                baseUrl = "https://api.bing.com/osjson.aspx",
                queryParameter = "query",
                format = SearchSuggestionFormat.OpenSearch,
            )
            SearchEngine.DuckDuckGo -> SearchSuggestionEndpoint(
                baseUrl = "https://duckduckgo.com/ac/",
                queryParameter = "q",
                format = SearchSuggestionFormat.DuckDuckGo,
            )
            SearchEngine.Yahoo -> SearchSuggestionEndpoint(
                baseUrl = "https://search.yahoo.com/sugg/gossip/gossip-us-ura/?output=sd1",
                queryParameter = "command",
                format = SearchSuggestionFormat.Yahoo,
            )
            SearchEngine.Brave -> SearchSuggestionEndpoint(
                baseUrl = "https://search.brave.com/api/suggest",
                queryParameter = "q",
                format = SearchSuggestionFormat.OpenSearch,
            )
            SearchEngine.Startpage -> SearchSuggestionEndpoint(
                baseUrl = "https://www.startpage.com/osuggestions?format=json",
                queryParameter = "q",
                format = SearchSuggestionFormat.OpenSearch,
            )
            SearchEngine.Ecosia -> SearchSuggestionEndpoint(
                baseUrl = "https://ac.ecosia.org/",
                queryParameter = "q",
                format = SearchSuggestionFormat.Ecosia,
            )
            SearchEngine.Qwant -> SearchSuggestionEndpoint(
                baseUrl = "https://api.qwant.com/v3/suggest?locale=en_US",
                queryParameter = "q",
                format = SearchSuggestionFormat.Qwant,
            )
        }
    }
}

/** Autocomplete endpoint for this engine. */
internal val SearchEngine.suggestionEndpoint: SearchSuggestionEndpoint
    get() = endpoints.getValue(this)
