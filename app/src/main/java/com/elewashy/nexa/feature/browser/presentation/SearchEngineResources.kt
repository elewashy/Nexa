package com.elewashy.nexa.feature.browser.presentation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine

/** Display name for this engine. */
@get:StringRes
val SearchEngine.labelRes: Int
    get() = when (this) {
        SearchEngine.Google -> R.string.search_engine_google
        SearchEngine.Bing -> R.string.search_engine_bing
        SearchEngine.DuckDuckGo -> R.string.search_engine_duckduckgo
        SearchEngine.Yahoo -> R.string.search_engine_yahoo
        SearchEngine.Brave -> R.string.search_engine_brave
        SearchEngine.Startpage -> R.string.search_engine_startpage
        SearchEngine.Ecosia -> R.string.search_engine_ecosia
        SearchEngine.Qwant -> R.string.search_engine_qwant
    }

/** Monochrome brand glyph (vector drawable) for this engine; tint it from the theme. */
@get:DrawableRes
val SearchEngine.iconRes: Int
    get() = when (this) {
        SearchEngine.Google -> R.drawable.ic_search_engine_google
        SearchEngine.Bing -> R.drawable.ic_search_engine_bing
        SearchEngine.DuckDuckGo -> R.drawable.ic_search_engine_duckduckgo
        SearchEngine.Yahoo -> R.drawable.ic_search_engine_yahoo
        SearchEngine.Brave -> R.drawable.ic_search_engine_brave
        SearchEngine.Startpage -> R.drawable.ic_search_engine_startpage
        SearchEngine.Ecosia -> R.drawable.ic_search_engine_ecosia
        SearchEngine.Qwant -> R.drawable.ic_search_engine_qwant
    }
