package com.elewashy.nexa.feature.tabs.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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
 * [openerTabId] is the tab whose page opened this one (`window.open`, `target=_blank`). It drives
 * "Back at the start of history returns to the opener". Ids are AUTOINCREMENT, so an opener always
 * has a smaller id than its children and the relation can never form a cycle. When an opener is
 * closed the repository re-parents its children to the nearest surviving ancestor; the
 * `SET NULL` foreign key is the integrity backstop so a dangling pointer can never be stored.
 *
 * [lastAccessedAt] doubles as the restore fallback when the active pointer
 * is stale (crash mid-switch): the most recently used tab wins.
 *
 * The (potentially large) WebView navigation state lives in [TabSessionStateEntity] so the
 * frequent whole-workspace reads of this table never load blobs.
 */
@Entity(
    tableName = "tabs",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["id"],
            childColumns = ["opener_tab_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        // Serves byPosition()'s `ORDER BY is_pinned DESC, position`.
        Index(value = ["is_pinned", "position"]),
        Index(value = ["is_active"]),
        // Foreign-key child column: avoids a full scan on every parent delete.
        Index(value = ["opener_tab_id"]),
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
    @ColumnInfo(name = "opener_tab_id") val openerTabId: Long? = null,
)
