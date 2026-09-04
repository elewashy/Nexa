package com.elewashy.nexa.feature.history.data.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per meaningful navigation (visit), the standard browser history
 * model: chronological order, repeated visits visible, per-visit deletion,
 * and enough data for future sync/export.
 *
 * Indexes follow the real query patterns only: newest-first paging/search
 * (visitedAt) and latest-title updates / future per-URL operations (url).
 * Title search uses LIKE '%q%', which cannot use an index at this scale —
 * no premature index.
 */
@Entity(
    tableName = "history",
    indices = [
        Index(value = ["visitedAt"]),
        Index(value = ["url"]),
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    /** Epoch millis of this visit. */
    val visitedAt: Long,
)
