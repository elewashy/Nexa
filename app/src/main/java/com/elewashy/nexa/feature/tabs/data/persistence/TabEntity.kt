package com.elewashy.nexa.feature.tabs.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persistent browser tab. The whole tab set is the single implicit
 * workspace — there are no window/session tables.
 *
 * Ordering is a normalized integer [position]. Pinned rows always precede
 * unpinned rows, and every structural mutation rewrites positions atomically.
 * [isActive] holds the exactly-one active-tab invariant, enforced
 * transactionally by the repository.
 *
 * [lastAccessedAt] doubles as the restore fallback when the active pointer
 * is stale (crash mid-switch): the most recently used tab wins.
 */
@Entity(
    tableName = "tabs",
    indices = [
        Index(value = ["position"]),
        Index(value = ["is_pinned", "position"]),
        Index(value = ["is_active"]),
    ]
)
data class TabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val position: Int,
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long,
)
