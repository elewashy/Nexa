package com.elewashy.nexa.feature.bookmarks.domain.model

data class BookmarkFolder(
    val id: Long,
    val title: String,
    val parentId: Long?,
    val position: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val itemCount: Int = 0,
)

enum class BookmarkSort {
    Manual,
    Newest,
    Oldest,
    LastOpened,
    TitleAscending,
    TitleDescending,
}

enum class BookmarkViewMode { Visual, Compact }
