package com.elewashy.nexa.feature.history.domain.model

/** Lightweight history projection used by the browser omnibox. */
data class HistorySuggestion(
    val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val visitCount: Int,
)
