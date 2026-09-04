package com.elewashy.nexa.feature.history.domain.model

/** UI-facing history visit; the Room entity never leaves the data layer. */
data class HistoryItem(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long,
)
