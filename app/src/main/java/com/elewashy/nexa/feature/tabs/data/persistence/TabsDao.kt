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

    @Query("UPDATE tabs SET is_active = 0")
    suspend fun clearActive()

    @Query("UPDATE tabs SET is_active = 1 WHERE id = :id")
    suspend fun setActive(id: Long): Int

    @Query("UPDATE tabs SET url = :url WHERE id = :id")
    suspend fun updateUrl(id: Long, url: String)

    @Query("UPDATE tabs SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE tabs SET last_accessed_at = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    @Query("UPDATE tabs SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinned(id: Long, isPinned: Boolean): Int

    @Query("UPDATE tabs SET is_pinned = :isPinned WHERE id IN (:ids)")
    suspend fun updatePinned(ids: Set<Long>, isPinned: Boolean): Int

    @Query("UPDATE tabs SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int): Int

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tabs WHERE id IN (:ids)")
    suspend fun delete(ids: Set<Long>)

    @Query("DELETE FROM tabs")
    suspend fun deleteAll()

    /** Insert a tab as the new active one, atomically. */
    @Transaction
    suspend fun insertAndActivate(entity: TabEntity): Long {
        clearActive()
        val id = insert(entity)
        setActive(id)
        return id
    }

    /** Move the active pointer, atomically. */
    @Transaction
    suspend fun activate(id: Long) {
        clearActive()
        setActive(id)
    }

    /** Applies pin state and canonical positions as one durable mutation. */
    @Transaction
    suspend fun setPinnedAndOrder(id: Long, isPinned: Boolean, orderedIds: List<Long>) {
        updatePinned(id, isPinned)
        updatePositions(orderedIds)
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

    /** Deletes, optionally moves the active pointer, and closes position gaps atomically. */
    @Transaction
    suspend fun deleteActivateAndReorder(id: Long, nextId: Long?, orderedIds: List<Long>) {
        delete(id)
        if (nextId != null) {
            clearActive()
            setActive(nextId)
        }
        updatePositions(orderedIds)
    }

    /** Deletes a selection, optionally moves the active pointer, and closes gaps atomically. */
    @Transaction
    suspend fun deleteActivateAndReorder(
        ids: Set<Long>,
        nextId: Long?,
        orderedIds: List<Long>,
    ) {
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
        val id = insert(entity)
        setActive(id)
        return id
    }

    private suspend fun updatePositions(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { position, id -> updatePosition(id, position) }
    }
}
