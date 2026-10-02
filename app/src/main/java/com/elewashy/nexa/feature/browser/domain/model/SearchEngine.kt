package com.elewashy.nexa.feature.browser.domain.model

import android.net.Uri
import androidx.core.net.toUri
import com.elewashy.nexa.core.common.BrowserUrls

/**
 * A web search provider the user can run address-bar queries against.
 *
 * Owns the engine's browsing contract: the home page opened for new tabs and
 * the Home action, the result-page URL builder, and result-page query
 * extraction (used to label history entries). UI resources live in the
 * presentation layer and autocomplete API contracts live in the data layer.
 */
enum class SearchEngine(
    val storedValue: Int,
    /** Page opened for new tabs and the Home action while this engine is selected. */
    val homeUrl: String,
    /** Result-page prefix; the percent-encoded query is appended directly. */
    private val searchUrlPrefix: String,
    /** Host fragment identifying this engine's result pages (matches any TLD). */
    private val searchPageHost: String,
    /** Exact path of this engine's result page. */
    private val searchPagePath: String,
    /** Query parameter carrying the search text on the result page. */
    private val searchPageQueryParam: String,
) {
    Google(
        storedValue = 0,
        homeUrl = BrowserUrls.HOME,
        searchUrlPrefix = "https://www.google.com/search?q=",
        searchPageHost = "google.",
        searchPagePath = "/search",
        searchPageQueryParam = "q",
    ),
    Bing(
        storedValue = 1,
        homeUrl = "https://www.bing.com/",
        searchUrlPrefix = "https://www.bing.com/search?q=",
        searchPageHost = "bing.com",
        searchPagePath = "/search",
        searchPageQueryParam = "q",
    ),
    DuckDuckGo(
        storedValue = 2,
        homeUrl = "https://duckduckgo.com/",
        searchUrlPrefix = "https://duckduckgo.com/?q=",
        searchPageHost = "duckduckgo.com",
        searchPagePath = "/",
        searchPageQueryParam = "q",
    ),
    Yahoo(
        storedValue = 3,
        homeUrl = "https://www.yahoo.com/",
        searchUrlPrefix = "https://search.yahoo.com/search?p=",
        searchPageHost = "search.yahoo.com",
        searchPagePath = "/search",
        searchPageQueryParam = "p",
    ),
    Brave(
        storedValue = 4,
        homeUrl = "https://search.brave.com/",
        searchUrlPrefix = "https://search.brave.com/search?q=",
        searchPageHost = "search.brave.com",
        searchPagePath = "/search",
        searchPageQueryParam = "q",
    ),
    Startpage(
        storedValue = 5,
        homeUrl = "https://www.startpage.com/",
        searchUrlPrefix = "https://www.startpage.com/sp/search?query=",
        searchPageHost = "startpage.com",
        searchPagePath = "/sp/search",
        searchPageQueryParam = "query",
    ),
    Ecosia(
        storedValue = 6,
        homeUrl = "https://www.ecosia.org/",
        searchUrlPrefix = "https://www.ecosia.org/search?q=",
        searchPageHost = "ecosia.org",
        searchPagePath = "/search",
        searchPageQueryParam = "q",
    ),
    Qwant(
        storedValue = 7,
        homeUrl = "https://www.qwant.com/",
        searchUrlPrefix = "https://www.qwant.com/?q=",
        searchPageHost = "qwant.com",
        searchPagePath = "/",
        searchPageQueryParam = "q",
    );

    /** Web-search URL for a free-text [query]; the query is percent-encoded. */
    fun searchUrl(query: String): String = searchUrlPrefix + Uri.encode(query)

    /**
     * The search text if [url] is one of this engine's result pages, else null.
     * Used to label result-page history entries with the query instead of the host.
     */
    fun searchPageQuery(url: String): String? {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains(searchPageHost)) return null
        if (uri.path.orEmpty() != searchPagePath) return null
        return uri.getQueryParameter(searchPageQueryParam)?.takeUnless { it.isBlank() }
    }

    companion object {
        /** Engine used before the user picks one and for unknown persisted values. */
        val DEFAULT = Google

        fun fromStoredValue(value: Int): SearchEngine =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT

        /** The search text if [url] is a result page of any supported engine, else null. */
        fun searchQueryFromUrl(url: String): String? =
            entries.firstNotNullOfOrNull { it.searchPageQuery(url) }
    }
}
