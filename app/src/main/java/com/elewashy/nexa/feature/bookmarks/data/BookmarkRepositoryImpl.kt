package com.elewashy.nexa.feature.bookmarks.data

import com.elewashy.nexa.core.util.SafeUrls.isSafeLoadableUrl
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarkEntity
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarkFolderEntity
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarkFolderWithCount
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarkUpdateOutcome
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarksDao
import com.elewashy.nexa.feature.bookmarks.data.persistence.DeletedFolderSnapshot
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkFolder
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkItem
import com.elewashy.nexa.feature.bookmarks.domain.model.DeletedBookmarkFolder
import com.elewashy.nexa.feature.bookmarks.domain.model.limitBookmarkTitle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarksDao,
) : BookmarkRepository {

    override fun observeBookmarks(query: String): Flow<List<BookmarkItem>> {
        val source = if (query.isBlank()) dao.observeAll() else dao.observeSearch("%$query%")
        return source.map { rows -> rows.map { it.toItem() } }
    }

    override fun observeBookmarkedUrls(): Flow<Set<String>> =
        dao.observeUrls().map { urls -> urls.toHashSet() }

    override fun observeBookmarksInFolder(folderId: Long?): Flow<List<BookmarkItem>> =
        dao.observeInFolder(folderId).map { rows -> rows.map { it.toItem() } }

    override fun observeFolders(parentId: Long?): Flow<List<BookmarkFolder>> =
        dao.observeFolders(parentId).map { rows -> rows.map { it.toItem() } }

    override fun observeAllFolders(): Flow<List<BookmarkFolder>> =
        dao.observeAllFolders().map { rows -> rows.map { it.toItem() } }

    override suspend fun toggle(url: String, title: String): Boolean {
        if (!isSafeLoadableUrl(url)) return false
        val now = System.currentTimeMillis()
        return dao.toggle(
            BookmarkEntity(url = url, title = title, createdAt = now, updatedAt = now)
        )
    }

    override suspend fun updateTitle(id: Long, title: String) {
        dao.updateTitle(id, title, System.currentTimeMillis())
    }

    override suspend fun updateBookmark(
        id: Long,
        title: String,
        url: String,
        folderId: Long?,
    ): BookmarkUpdateResult {
        val safeTitle = title.trim().limitBookmarkTitle()
        val safeUrl = url.trim()
        if (safeTitle.isEmpty() || !isSafeLoadableUrl(safeUrl)) {
            return BookmarkUpdateResult.INVALID_INPUT
        }
        if (folderId != null && dao.folderById(folderId) == null) {
            return BookmarkUpdateResult.INVALID_INPUT
        }
        val now = System.currentTimeMillis()
        return when (
            dao.updateBookmark(
                id = id,
                title = safeTitle,
                url = safeUrl,
                folderId = folderId,
                movedPosition = now,
                updatedAt = now,
            )
        ) {
            BookmarkUpdateOutcome.UPDATED -> BookmarkUpdateResult.UPDATED
            BookmarkUpdateOutcome.NOT_FOUND -> BookmarkUpdateResult.NOT_FOUND
            BookmarkUpdateOutcome.URL_CONFLICT -> BookmarkUpdateResult.URL_ALREADY_EXISTS
        }
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun reinsert(item: BookmarkItem) {
        // IGNORE conflict: if the same URL was re-bookmarked while the undo
        // snackbar was pending, the live bookmark wins — the undo no-ops
        // instead of crashing on the unique url index.
        dao.insert(
            BookmarkEntity(
                id = item.id,
                url = item.url,
                title = item.title,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                folderId = item.folderId,
                position = item.position,
                lastOpenedAt = item.lastOpenedAt,
            )
        )
    }

    override suspend fun byUrl(url: String): BookmarkItem? =
        url.takeIf { it.isNotBlank() }?.let { dao.byUrl(it)?.toItem() }

    override fun observeIsBookmarked(url: String): Flow<Boolean> =
        if (url.isBlank()) flowOf(false) else dao.observeExists(url)

    override suspend fun folderById(id: Long): BookmarkFolder? = dao.folderById(id)?.toItem()

    override suspend fun createFolder(title: String, parentId: Long?): Long {
        val now = System.currentTimeMillis()
        return dao.insertFolder(
            BookmarkFolderEntity(
                title = title,
                parentId = parentId,
                position = now,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun renameFolder(id: Long, title: String) {
        dao.updateFolderTitle(id, title, System.currentTimeMillis())
    }

    override suspend fun deleteFolder(folder: BookmarkFolder): DeletedBookmarkFolder =
        dao.deleteFolder(folder.toEntity()).toDomain()

    override suspend fun restoreDeletedFolders(folders: List<DeletedBookmarkFolder>) {
        // Restore parents before children when multiple same-level folders were deleted.
        folders.sortedBy { it.folder.parentId != null }.forEach { snapshot ->
            dao.restoreDeletedFolder(snapshot.toEntity())
        }
    }

    override suspend fun moveBookmark(id: Long, folderId: Long?) {
        dao.moveBookmark(id, folderId, System.currentTimeMillis())
    }

    override suspend fun moveFolder(id: Long, parentId: Long?) {
        // Enforce the acyclic folder invariant at the data boundary as well as in the UI.
        var ancestorId = parentId
        while (ancestorId != null) {
            if (ancestorId == id) return
            ancestorId = dao.folderById(ancestorId)?.parentId ?: return
        }
        dao.moveFolder(id, parentId, System.currentTimeMillis())
    }

    override suspend fun markOpened(id: Long) {
        dao.markOpened(id, System.currentTimeMillis())
    }

    override suspend fun swapOrder(first: BookmarkItem, second: BookmarkItem) {
        dao.swapPositions(first.toEntity(), second.toEntity())
    }

    override suspend fun moveSiblingToIndex(
        id: Long,
        isFolder: Boolean,
        parentId: Long?,
        targetIndex: Int,
    ) {
        dao.moveSiblingToIndex(id, isFolder, parentId, targetIndex)
    }

    override suspend fun moveBookmarkToIndex(id: Long, folderId: Long?, targetIndex: Int) {
        dao.moveBookmarkToIndex(id, folderId, targetIndex)
    }

    override suspend fun swapFolderOrder(first: BookmarkFolder, second: BookmarkFolder) {
        dao.swapFolderPositions(first.toEntity(), second.toEntity())
    }

    override suspend fun moveFolderToIndex(id: Long, parentId: Long?, targetIndex: Int) {
        dao.moveFolderToIndex(id, parentId, targetIndex)
    }

    private fun BookmarkEntity.toItem() = BookmarkItem(
        id = id,
        url = url,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        folderId = folderId,
        position = position,
        lastOpenedAt = lastOpenedAt,
    )

    private fun BookmarkItem.toEntity() = BookmarkEntity(
        id = id,
        url = url,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        folderId = folderId,
        position = position,
        lastOpenedAt = lastOpenedAt,
    )

    private fun BookmarkFolderEntity.toItem() = BookmarkFolder(
        id = id,
        title = title,
        parentId = parentId,
        position = position,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun BookmarkFolderWithCount.toItem() = BookmarkFolder(
        id = id,
        title = title,
        parentId = parentId,
        position = position,
        createdAt = createdAt,
        updatedAt = updatedAt,
        itemCount = itemCount,
    )

    private fun DeletedFolderSnapshot.toDomain() = DeletedBookmarkFolder(
        folder = folder.toItem(),
        bookmarkIds = bookmarkIds,
        childFolderIds = childFolderIds,
    )

    private fun DeletedBookmarkFolder.toEntity() = DeletedFolderSnapshot(
        folder = folder.toEntity(),
        bookmarkIds = bookmarkIds,
        childFolderIds = childFolderIds,
    )

    private fun BookmarkFolder.toEntity() = BookmarkFolderEntity(
        id = id,
        title = title,
        parentId = parentId,
        position = position,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
