package com.elewashy.nexa.feature.tabs.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Opaque navigation state (back/forward list, scroll and form state) of one tab, as produced by
 * the browser layer from `WebView.saveState`. One-to-one with [TabEntity]: the primary key is the
 * tab id and the `CASCADE` foreign key guarantees closing a tab can never orphan its state.
 *
 * Kept out of the `tabs` table on purpose — the workspace list is read on every structural
 * mutation and must stay small; a state blob is read only when a tab's WebView is materialized.
 */
@Entity(
    tableName = "tab_session_states",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["id"],
            childColumns = ["tab_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class TabSessionStateEntity(
    @PrimaryKey @ColumnInfo(name = "tab_id") val tabId: Long,
    @ColumnInfo(name = "state", typeAffinity = ColumnInfo.BLOB) val state: ByteArray,
)
