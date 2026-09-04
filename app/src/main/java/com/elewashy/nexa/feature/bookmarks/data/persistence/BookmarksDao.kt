package com.elewashy.nexa.feature.bookmarks.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private data class BookmarkSiblingOrderEntry(
    val id: Long,
    val isFolder: Boolean,
    val position: Long,
)

data class DeletedFolderSnapshot(
    val folder: BookmarkFolderEntity,
    val bookmarkIds: List<Long>,
    val childFolderIds: List<Long>,
)

enum class BookmarkUpdateOutcome {
    UPDATED,
    NOT_FOUND,
    URL_CONFLICT,
}

@Dao
interface BookmarksDao {

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query(
        "SELECT * FROM bookmarks WHERE url LIKE :query OR title LIKE :query " +
            "ORDER BY created_at DESC, id DESC"
    )
    fun observeSearch(query: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): BookmarkEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeExists(url: String): Flow<Boolean>

    /** URL-only projection for surfaces that need membership, not full rows. */
    @Query("SELECT url FROM bookmarks")
    fun observeUrls(): Flow<List<String>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun byId(id: Long): BookmarkEntity?

    /**
     * Linearizable read/modify/write toggle. Room holds the transaction across
     * both the existence check and mutation, so two callers cannot both decide
     * from the same stale state. SQLite already serializes write transactions;
     * this adds no repository-global lock or retained per-URL lock map.
     */
    @Transaction
    suspend fun toggle(entity: BookmarkEntity): Boolean {
        val existing = byUrl(entity.url)
        if (existing != null) {
            deleteById(existing.id)
            return false
        }
        // IGNORE remains a final unique-index guard for external writers.
        // A conflict means the requested URL is present, which is the "added"
        // outcome from this caller's perspective.
        return insert(entity) != -1L || byUrl(entity.url) != null
    }

    /**
     * IGNORE on conflict: the unique URL index makes toggle/undo conflicts
     * converge on one row instead of crashing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long): Int

    @Query(
        "UPDATE bookmarks SET title = :title, url = :url, folder_id = :folderId, " +
            "position = CASE WHEN " +
            "(folder_id = :folderId OR (folder_id IS NULL AND :folderId IS NULL)) " +
            "THEN position ELSE :movedPosition END, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateBookmarkRow(
        id: Long,
        title: String,
        url: String,
        folderId: Long?,
        movedPosition: Long,
        updatedAt: Long,
    ): Int

    /**
     * Edits all user-controlled bookmark fields as one transaction. The URL ownership check keeps
     * the unique-index policy non-destructive: editing to another bookmark's URL changes neither
     * row. A move receives a fresh position while an edit within the same folder preserves order.
     */
    @Transaction
    suspend fun updateBookmark(
        id: Long,
        title: String,
        url: String,
        folderId: Long?,
        movedPosition: Long,
        updatedAt: Long,
    ): BookmarkUpdateOutcome {
        val existing = byId(id) ?: return BookmarkUpdateOutcome.NOT_FOUND
        val urlOwner = byUrl(url)
        if (urlOwner != null && urlOwner.id != existing.id) {
            return BookmarkUpdateOutcome.URL_CONFLICT
        }
        return if (
            updateBookmarkRow(id, title, url, folderId, movedPosition, updatedAt) == 1
        ) {
            BookmarkUpdateOutcome.UPDATED
        } else {
            BookmarkUpdateOutcome.NOT_FOUND
        }
    }

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT * FROM bookmarks WHERE " +
            "(folder_id = :folderId OR (folder_id IS NULL AND :folderId IS NULL)) " +
            "ORDER BY position ASC, id ASC"
    )
    fun observeInFolder(folderId: Long?): Flow<List<BookmarkEntity>>

    @Query(
        "SELECT folder.*, " +
            "((SELECT COUNT(*) FROM bookmarks WHERE folder_id = folder.id) + " +
            "(SELECT COUNT(*) FROM bookmark_folders WHERE parent_id = folder.id)) AS item_count " +
            "FROM bookmark_folders AS folder WHERE " +
            "(folder.parent_id = :parentId OR (folder.parent_id IS NULL AND :parentId IS NULL)) " +
            "ORDER BY folder.position ASC, folder.id ASC"
    )
    fun observeFolders(parentId: Long?): Flow<List<BookmarkFolderWithCount>>

    @Query(
        "SELECT folder.*, " +
            "((SELECT COUNT(*) FROM bookmarks WHERE folder_id = folder.id) + " +
            "(SELECT COUNT(*) FROM bookmark_folders WHERE parent_id = folder.id)) AS item_count " +
            "FROM bookmark_folders AS folder ORDER BY folder.title COLLATE NOCASE ASC, folder.id ASC"
    )
    fun observeAllFolders(): Flow<List<BookmarkFolderWithCount>>

    @Query("SELECT * FROM bookmark_folders WHERE id = :id")
    suspend fun folderById(id: Long): BookmarkFolderEntity?

    @Insert
    suspend fun insertFolder(folder: BookmarkFolderEntity): Long

    @Query("UPDATE bookmark_folders SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateFolderTitle(id: Long, title: String, updatedAt: Long): Int

    @Query("UPDATE bookmarks SET folder_id = :folderId, position = :position WHERE id = :id")
    suspend fun moveBookmark(id: Long, folderId: Long?, position: Long): Int

    @Query("UPDATE bookmark_folders SET parent_id = :parentId, position = :position WHERE id = :id")
    suspend fun moveFolder(id: Long, parentId: Long?, position: Long): Int

    @Query("UPDATE bookmarks SET last_opened_at = :openedAt WHERE id = :id")
    suspend fun markOpened(id: Long, openedAt: Long): Int

    @Query("UPDATE bookmarks SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Long): Int

    @Query(
        "SELECT * FROM bookmarks WHERE " +
            "(folder_id = :folderId OR (folder_id IS NULL AND :folderId IS NULL)) " +
            "ORDER BY position ASC, id ASC"
    )
    suspend fun bookmarksInFolder(folderId: Long?): List<BookmarkEntity>

    /**
     * Moves one sibling to an absolute index. Normally this is a single-row update using a
     * fractional position. The whole sibling set is normalized only when adjacent positions no
     * longer have an integer gap. Room keeps the read and all writes atomic.
     */
    /**
     * Atomically reorders a folder or bookmark in the single mixed sibling sequence rendered by
     * the UI. Both tables share the same position domain, so folder/bookmark boundaries do not
     * constrain movement.
     */
    @Transaction
    suspend fun moveSiblingToIndex(
        id: Long,
        isFolder: Boolean,
        parentId: Long?,
        targetIndex: Int,
    ) {
        val ordered = buildList {
            foldersInParent(parentId).forEach {
                add(BookmarkSiblingOrderEntry(it.id, isFolder = true, it.position))
            }
            bookmarksInFolder(parentId).forEach {
                add(BookmarkSiblingOrderEntry(it.id, isFolder = false, it.position))
            }
        }.sortedWith(
            compareBy<BookmarkSiblingOrderEntry> { it.position }
                .thenByDescending { it.isFolder }
                .thenBy { it.id },
        )
        val sourceIndex = ordered.indexOfFirst { it.id == id && it.isFolder == isFolder }
        if (sourceIndex < 0) return
        val destination = targetIndex.coerceIn(0, ordered.lastIndex)
        if (sourceIndex == destination) return
        val moved = ordered.toMutableList().apply { add(destination, removeAt(sourceIndex)) }
        val position = positionAt(moved, destination) { it.position }
        if (position != null) {
            if (isFolder) updateFolderPosition(id, position) else updatePosition(id, position)
        } else {
            moved.forEachIndexed { index, item ->
                val normalized = normalizedPosition(index)
                if (item.isFolder) updateFolderPosition(item.id, normalized)
                else updatePosition(item.id, normalized)
            }
        }
    }

    @Transaction
    suspend fun moveBookmarkToIndex(id: Long, folderId: Long?, targetIndex: Int) {
        val ordered = bookmarksInFolder(folderId)
        val sourceIndex = ordered.indexOfFirst { it.id == id }
        if (sourceIndex < 0) return
        val destination = targetIndex.coerceIn(0, ordered.lastIndex)
        if (sourceIndex == destination) return
        val moved = ordered.toMutableList().apply { add(destination, removeAt(sourceIndex)) }
        val position = positionAt(moved, destination) { it.position }
        if (position != null) {
            updatePosition(id, position)
        } else {
            moved.forEachIndexed { index, item -> updatePosition(item.id, normalizedPosition(index)) }
        }
    }

    @Transaction
    suspend fun swapPositions(first: BookmarkEntity, second: BookmarkEntity) {
        if (first.position != second.position) {
            updatePosition(first.id, second.position)
            updatePosition(second.id, first.position)
        } else if (first.id < second.id) {
            // Equal millisecond positions are possible for rapid inserts. Preserve the requested
            // downward swap instead of letting the id tie-breaker keep the old order.
            updatePosition(first.id, first.position + 1)
            updatePosition(second.id, second.position)
        } else {
            updatePosition(first.id, first.position)
            updatePosition(second.id, second.position + 1)
        }
    }

    @Query("UPDATE bookmark_folders SET position = :position WHERE id = :id")
    suspend fun updateFolderPosition(id: Long, position: Long): Int

    @Query(
        "SELECT * FROM bookmark_folders WHERE " +
            "(parent_id = :parentId OR (parent_id IS NULL AND :parentId IS NULL)) " +
            "ORDER BY position ASC, id ASC"
    )
    suspend fun foldersInParent(parentId: Long?): List<BookmarkFolderEntity>

    @Transaction
    suspend fun moveFolderToIndex(id: Long, parentId: Long?, targetIndex: Int) {
        val ordered = foldersInParent(parentId)
        val sourceIndex = ordered.indexOfFirst { it.id == id }
        if (sourceIndex < 0) return
        val destination = targetIndex.coerceIn(0, ordered.lastIndex)
        if (sourceIndex == destination) return
        val moved = ordered.toMutableList().apply { add(destination, removeAt(sourceIndex)) }
        val position = positionAt(moved, destination) { it.position }
        if (position != null) {
            updateFolderPosition(id, position)
        } else {
            moved.forEachIndexed { index, item -> updateFolderPosition(item.id, normalizedPosition(index)) }
        }
    }

    @Transaction
    suspend fun swapFolderPositions(first: BookmarkFolderEntity, second: BookmarkFolderEntity) {
        if (first.position != second.position) {
            updateFolderPosition(first.id, second.position)
            updateFolderPosition(second.id, first.position)
        } else if (first.id < second.id) {
            updateFolderPosition(first.id, first.position + 1)
            updateFolderPosition(second.id, second.position)
        } else {
            updateFolderPosition(first.id, first.position)
            updateFolderPosition(second.id, second.position + 1)
        }
    }

    @Query("UPDATE bookmarks SET folder_id = :parentId WHERE folder_id = :folderId")
    suspend fun moveBookmarksToParent(folderId: Long, parentId: Long?): Int

    @Query("UPDATE bookmark_folders SET parent_id = :parentId WHERE parent_id = :folderId")
    suspend fun moveChildFoldersToParent(folderId: Long, parentId: Long?): Int

    @Query("DELETE FROM bookmark_folders WHERE id = :id")
    suspend fun deleteFolderById(id: Long)

    @Query("SELECT id FROM bookmarks WHERE folder_id = :folderId")
    suspend fun bookmarkIdsInFolder(folderId: Long): List<Long>

    @Query("SELECT id FROM bookmark_folders WHERE parent_id = :folderId")
    suspend fun childFolderIds(folderId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restoreFolderEntity(folder: BookmarkFolderEntity): Long

    @Query(
        "UPDATE bookmarks SET folder_id = :folderId WHERE id = :bookmarkId AND " +
            "(folder_id = :expectedParentId OR (folder_id IS NULL AND :expectedParentId IS NULL))"
    )
    suspend fun restoreBookmarkParent(bookmarkId: Long, folderId: Long, expectedParentId: Long?): Int

    @Query(
        "UPDATE bookmark_folders SET parent_id = :folderId WHERE id = :childId AND " +
            "(parent_id = :expectedParentId OR (parent_id IS NULL AND :expectedParentId IS NULL))"
    )
    suspend fun restoreChildFolderParent(childId: Long, folderId: Long, expectedParentId: Long?): Int

    @Transaction
    suspend fun deleteFolder(folder: BookmarkFolderEntity): DeletedFolderSnapshot {
        val snapshot = DeletedFolderSnapshot(
            folder = folder,
            bookmarkIds = bookmarkIdsInFolder(folder.id),
            childFolderIds = childFolderIds(folder.id),
        )
        moveBookmarksToParent(folder.id, folder.parentId)
        moveChildFoldersToParent(folder.id, folder.parentId)
        deleteFolderById(folder.id)
        return snapshot
    }

    @Transaction
    suspend fun restoreDeletedFolder(snapshot: DeletedFolderSnapshot) {
        if (restoreFolderEntity(snapshot.folder) == -1L) return
        snapshot.bookmarkIds.forEach { id ->
            restoreBookmarkParent(id, snapshot.folder.id, snapshot.folder.parentId)
        }
        snapshot.childFolderIds.forEach { id ->
            restoreChildFolderParent(id, snapshot.folder.id, snapshot.folder.parentId)
        }
    }

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    private fun <T> positionAt(items: List<T>, index: Int, positionOf: (T) -> Long): Long? {
        val previous = items.getOrNull(index - 1)?.let(positionOf)
        val next = items.getOrNull(index + 1)?.let(positionOf)
        return when {
            previous == null && next == null -> 0L
            previous == null && next != null && next >= Long.MIN_VALUE + POSITION_STEP ->
                next - POSITION_STEP
            next == null && previous != null && previous <= Long.MAX_VALUE - POSITION_STEP ->
                previous + POSITION_STEP
            previous != null && next != null && next != Long.MIN_VALUE && previous < next - 1L ->
                (previous and next) + ((previous xor next) shr 1)
            else -> null
        }
    }

    private fun normalizedPosition(index: Int): Long = index.toLong() * POSITION_STEP

    private companion object {
        const val POSITION_STEP = 1_024L
    }
}
