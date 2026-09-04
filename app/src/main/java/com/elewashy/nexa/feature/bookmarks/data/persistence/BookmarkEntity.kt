package com.elewashy.nexa.feature.bookmarks.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One bookmark per URL — the UNIQUE index on [url] is the schema's explicit
 * duplicate policy: re-adding an already-bookmarked URL toggles it off
 * instead of creating a second row.
 *
 * Listing is chronological ([createdAt] DESC). Search uses LIKE '%q%' on
 * url/title, which cannot use an index at bookmark scale — same rationale as
 * history; no premature index. A future folders/tags version adds a column
 * or join table without touching these row semantics.
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["folder_id"]),
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    val position: Long = createdAt,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long = 0,
)
