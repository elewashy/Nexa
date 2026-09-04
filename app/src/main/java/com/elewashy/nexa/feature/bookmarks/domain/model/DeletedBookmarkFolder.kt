package com.elewashy.nexa.feature.bookmarks.domain.model

/** Complete structural snapshot needed to undo a folder deletion safely. */
data class DeletedBookmarkFolder(
    val folder: BookmarkFolder,
    val bookmarkIds: List<Long>,
    val childFolderIds: List<Long>,
)
