package com.elewashy.nexa.core.common

import android.net.Uri

/** Browser URL constants shared across features. */
object BrowserUrls {
    /** The home/new-tab page. A real URL, not a sentinel route. */
    const val HOME = "https://www.google.com/"

    private const val SEARCH_ENDPOINT = "https://www.google.com/search?q="

    /** Web-search URL for a free-text [query]; the query is percent-encoded. */
    fun searchUrl(query: String): String = SEARCH_ENDPOINT + Uri.encode(query)
}
