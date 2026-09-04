package com.elewashy.nexa.feature.bookmarks.data

import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkFolder
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkItem
import com.elewashy.nexa.feature.bookmarks.domain.model.DeletedBookmarkFolder
import kotlinx.coroutines.flow.Flow

enum class BookmarkUpdateResult {
    UPDATED,
    INVALID_INPUT,
    NOT_FOUND,
    URL_ALREADY_EXISTS,
}

/**
 * Public boundary for bookmarks. Room types never cross this interface.
 *
 * Duplicate policy: one bookmark per URL. [toggle] adds the URL, or removes
 * it when it is already bookmarked — the star in the browser menu reflects
 * this state.
 */
interface BookmarkRepository {

    /** Bookmarks newest-first; a blank [query] returns everything. */
    fun observeBookmarks(query: String): Flow<List<BookmarkItem>>

    /** Every bookmarked URL, for cheap membership checks across many pages (tab overview). */
    fun observeBookmarkedUrls(): Flow<Set<String>>

    fun observeBookmarksInFolder(folderId: Long?): Flow<List<BookmarkItem>>

    fun observeFolders(parentId: Long?): Flow<List<BookmarkFolder>>

    /** All folders, flattened and title-sorted, for edit/move destination pickers. */
    fun observeAllFolders(): Flow<List<BookmarkFolder>>

    /**
     * Adds a bookmark for [url], or removes the existing one when the URL
     * is already bookmarked. Returns true when a bookmark was added.
     * Calls are linearizable: two concurrent toggles behave as two sequential
     * toggles in an unspecified caller order.
     */
    suspend fun toggle(url: String, title: String): Boolean

    suspend fun updateTitle(id: Long, title: String)

    /**
     * Atomically edits title, URL, and location. Blank values are rejected and an existing URL is
     * never replaced or deleted.
     */
    suspend fun updateBookmark(
        id: Long,
        title: String,
        url: String,
        folderId: Long?,
    ): BookmarkUpdateResult

    suspend fun delete(id: Long)

    /** Restores a row removed by [delete] (undo support). */
    suspend fun reinsert(item: BookmarkItem)

    /** The bookmark for [url], or null. */
    suspend fun byUrl(url: String): BookmarkItem?

    /**
     * Emits whether [url] is bookmarked and stays synchronized with Room.
     * This drives the browser icon across navigation, tab switches, process
     * recreation, and edits made from the bookmarks screen.
     */
    fun observeIsBookmarked(url: String): Flow<Boolean>

    suspend fun folderById(id: Long): BookmarkFolder?

    suspend fun createFolder(title: String, parentId: Long?): Long

    suspend fun renameFolder(id: Long, title: String)

    suspend fun deleteFolder(folder: BookmarkFolder): DeletedBookmarkFolder

    suspend fun restoreDeletedFolders(folders: List<DeletedBookmarkFolder>)

    suspend fun moveBookmark(id: Long, folderId: Long?)

    suspend fun moveFolder(id: Long, parentId: Long?)

    suspend fun markOpened(id: Long)

    suspend fun swapOrder(first: BookmarkItem, second: BookmarkItem)

    /** Atomically moves either item type within the mixed folder/bookmark sibling sequence. */
    suspend fun moveSiblingToIndex(id: Long, isFolder: Boolean, parentId: Long?, targetIndex: Int)

    /** Atomically moves a bookmark among siblings; out-of-range indices are safely clamped. */
    suspend fun moveBookmarkToIndex(id: Long, folderId: Long?, targetIndex: Int)

    suspend fun swapFolderOrder(first: BookmarkFolder, second: BookmarkFolder)

    /** Atomically moves a folder among siblings; out-of-range indices are safely clamped. */
    suspend fun moveFolderToIndex(id: Long, parentId: Long?, targetIndex: Int)
}
