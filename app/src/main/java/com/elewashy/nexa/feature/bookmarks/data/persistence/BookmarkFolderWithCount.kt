package com.elewashy.nexa.feature.bookmarks.data.persistence

import androidx.room.ColumnInfo

/** Read model for a folder and the number of direct bookmarks and subfolders it contains. */
data class BookmarkFolderWithCount(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "parent_id") val parentId: Long?,
    val position: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "item_count") val itemCount: Int,
)
