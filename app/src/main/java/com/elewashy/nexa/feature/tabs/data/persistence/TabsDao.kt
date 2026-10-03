package com.elewashy.nexa.feature.tabs.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TabsDao {

    @Query("SELECT * FROM tabs ORDER BY is_pinned DESC, position ASC, id ASC")
    suspend fun byPosition(): List<TabEntity>

    @Query("SELECT * FROM tabs WHERE is_active = 1 LIMIT 1")
    suspend fun activeTab(): TabEntity?

    @Query("SELECT COUNT(*) FROM tabs")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: TabEntity): Long

    @Query("UPDATE tabs SET is_active = 0 WHERE is_active = 1")
    suspend fun clearActive()

    @Query("UPDATE tabs SET is_active = 1 WHERE id = :id")
    suspend fun setActive(id: Long): Int

    @Query("UPDATE tabs SET url = :url WHERE id = :id")
    suspend fun updateUrl(id: Long, url: String)

    @Query("UPDATE tabs SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE tabs SET last_accessed_at = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    @Query("UPDATE tabs SET is_pinned = :isPinned WHERE id IN (:ids)")
    suspend fun updatePinned(ids: Set<Long>, isPinned: Boolean): Int

    /** Skips rows already in place so a reorder rewrites only the pages that changed. */
    @Query("UPDATE tabs SET position = :position WHERE id = :id AND position != :position")
    suspend fun updatePosition(id: Long, position: Int): Int

    @Query("UPDATE tabs SET opener_tab_id = :openerTabId WHERE id = :id")
    suspend fun updateOpener(id: Long, openerTabId: Long?)

    /** Session state rows cascade via their foreign key. */
    @Query("DELETE FROM tabs WHERE id IN (:ids)")
    suspend fun delete(ids: Set<Long>)

    @Query("DELETE FROM tabs")
    suspend fun deleteAll()

    // ── Session state (one-to-one with tabs) ────────────────────────────

    @Query("SELECT state FROM tab_session_states WHERE tab_id = :tabId")
    suspend fun sessionState(tabId: Long): ByteArray?

    /**
     * Upserts a tab's navigation state only while the tab row exists. Coalesced writes can race a
     * close; the EXISTS guard turns a late write into a no-op instead of a foreign-key failure.
     */
    @Query(
        "INSERT OR REPLACE INTO tab_session_states (tab_id, state) " +
            "SELECT :tabId, :state WHERE EXISTS (SELECT 1 FROM tabs WHERE id = :tabId)"
    )
    suspend fun upsertSessionState(tabId: Long, state: ByteArray)

    @Query("DELETE FROM tab_session_states WHERE tab_id = :tabId")
    suspend fun deleteSessionState(tabId: Long)

    // ── Transactions ────────────────────────────────────────────────────

    /** Insert a tab as the new active one, atomically. */
    @Transaction
    suspend fun insertAndActivate(entity: TabEntity): Long {
        clearActive()
        val id = insert(entity)
        setActive(id)
        return id
    }

    /**
     * Re-inserts a previously closed tab as the active one at canonical [index] of
     * [orderedExistingIds], restoring its navigation [sessionState] in the same transaction.
     */
    @Transaction
    suspend fun insertAtAndActivate(
        entity: TabEntity,
        orderedExistingIds: List<Long>,
        index: Int,
        sessionState: ByteArray?,
    ): Long {
        val id = insertAndActivate(entity)
        val ordered = orderedExistingIds.toMutableList()
            .apply { add(index.coerceIn(0, size), id) }
        updatePositions(ordered)
        if (sessionState != null) upsertSessionState(id, sessionState)
        return id
    }

    /** Move the active pointer, atomically. */
    @Transaction
    suspend fun activate(id: Long) {
        clearActive()
        setActive(id)
    }

    /** Applies one pin state and canonical positions to a selection atomically. */
    @Transaction
    suspend fun setPinnedAndOrder(ids: Set<Long>, isPinned: Boolean, orderedIds: List<Long>) {
        if (ids.isNotEmpty()) updatePinned(ids, isPinned)
        updatePositions(orderedIds)
    }

    /** Applies a canonical reorder atomically. */
    @Transaction
    suspend fun reorder(orderedIds: List<Long>) {
        updatePositions(orderedIds)
    }

    /**
     * Closes a selection atomically: re-parents the closed tabs' children ([openerUpdates], applied
     * before the delete so the `SET NULL` foreign key never severs a surviving ancestor chain),
     * deletes the rows (session states cascade), optionally moves the active pointer, and closes
     * position gaps.
     */
    @Transaction
    suspend fun deleteActivateAndReorder(
        ids: Set<Long>,
        nextId: Long?,
        orderedIds: List<Long>,
        openerUpdates: Map<Long, Long?> = emptyMap(),
    ) {
        openerUpdates.forEach { (id, openerTabId) -> updateOpener(id, openerTabId) }
        if (ids.isNotEmpty()) delete(ids)
        if (nextId != null) {
            clearActive()
            setActive(nextId)
        }
        updatePositions(orderedIds)
    }

    /** Replaces the normal workspace with one active tab atomically. */
    @Transaction
    suspend fun replaceWithActive(entity: TabEntity): Long {
        deleteAll()
        return insertAndActivate(entity)
    }

    private suspend fun updatePositions(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { position, id -> updatePosition(id, position) }
    }
}
