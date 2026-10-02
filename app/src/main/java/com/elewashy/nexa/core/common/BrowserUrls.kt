package com.elewashy.nexa.core.common

/** Browser URL constants shared across features. */
object BrowserUrls {
    /**
     * The default home/new-tab page and the fallback for unsafe or missing tab URLs.
     * A real URL, not a sentinel route. Engine-specific home pages live on
     * [com.elewashy.nexa.feature.browser.domain.model.SearchEngine].
     */
    const val HOME = "https://www.google.com/"
}
