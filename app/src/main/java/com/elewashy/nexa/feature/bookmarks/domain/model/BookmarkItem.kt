package com.elewashy.nexa.feature.bookmarks.domain.model

/** A user-owned bookmark. Independent from history, tabs, and sessions. */
data class BookmarkItem(
    val id: Long,
    val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val folderId: Long? = null,
    val position: Long = createdAt,
    val lastOpenedAt: Long = 0,
)
