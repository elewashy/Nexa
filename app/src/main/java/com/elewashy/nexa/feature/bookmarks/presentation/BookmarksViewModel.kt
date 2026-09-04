package com.elewashy.nexa.feature.bookmarks.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.bookmarks.data.BookmarkRepository
import com.elewashy.nexa.feature.bookmarks.data.BookmarkUpdateResult
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkFolder
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkItem
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkSort
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkViewMode
import com.elewashy.nexa.feature.bookmarks.domain.model.limitBookmarkTitle
import com.elewashy.nexa.feature.bookmarks.domain.model.DeletedBookmarkFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val repository: BookmarkRepository,
) : ViewModel() {

    private val mutationMutex = Mutex()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _folderStack = MutableStateFlow<List<BookmarkFolder>>(emptyList())
    val currentFolder: StateFlow<BookmarkFolder?> = _folderStack
        .map(List<BookmarkFolder>::lastOrNull)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val currentFolderId = currentFolder
        .map { it?.id }
        .distinctUntilChanged()

    val folders: StateFlow<List<BookmarkFolder>> = currentFolderId
        .flatMapLatest(repository::observeFolders)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Flat folder list for a future edit destination picker, independent of current navigation. */
    val destinationFolders: StateFlow<List<BookmarkFolder>> = repository.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val sort = MutableStateFlow(BookmarkSort.Manual)
    val selectedSort: StateFlow<BookmarkSort> = sort.asStateFlow()

    private val viewMode = MutableStateFlow(BookmarkViewMode.Visual)
    val selectedViewMode: StateFlow<BookmarkViewMode> = viewMode.asStateFlow()

    private val rawBookmarks = currentFolderId
        .flatMapLatest(repository::observeBookmarksInFolder)

    val bookmarks: StateFlow<List<BookmarkItem>> = combine(
        rawBookmarks,
        _searchQuery
            .map(String::trim)
            .distinctUntilChanged()
            .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
        sort,
    ) { items, query, order ->
        val filtered = if (query.isEmpty()) {
            items
        } else {
            items.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true)
            }
        }
        when (order) {
            BookmarkSort.Manual -> filtered.sortedWith(compareBy(BookmarkItem::position, BookmarkItem::id))
            BookmarkSort.Newest -> filtered.sortedByDescending { it.createdAt }
            BookmarkSort.Oldest -> filtered.sortedBy { it.createdAt }
            BookmarkSort.LastOpened -> filtered.sortedWith(
                compareByDescending<BookmarkItem> { it.lastOpenedAt }.thenByDescending { it.createdAt }
            )
            BookmarkSort.TitleAscending -> filtered.sortedBy {
                it.title.ifBlank { it.url }.lowercase()
            }
            BookmarkSort.TitleDescending -> filtered.sortedByDescending {
                it.title.ifBlank { it.url }.lowercase()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _undoState = MutableStateFlow(BookmarkUndoState())
    val undoState: StateFlow<BookmarkUndoState> = _undoState.asStateFlow()

    private val _editingItem = MutableStateFlow<BookmarkItem?>(null)
    val editingItem: StateFlow<BookmarkItem?> = _editingItem.asStateFlow()

    private val _editingFolder = MutableStateFlow<BookmarkFolder?>(null)
    val editingFolder: StateFlow<BookmarkFolder?> = _editingFolder.asStateFlow()

    private val _editError = MutableStateFlow<BookmarkEditError?>(null)
    val editError: StateFlow<BookmarkEditError?> = _editError.asStateFlow()

    private val _movingItem = MutableStateFlow<BookmarkItem?>(null)
    val movingItem: StateFlow<BookmarkItem?> = _movingItem.asStateFlow()

    private val _showCreateFolder = MutableStateFlow(false)
    val showCreateFolder: StateFlow<Boolean> = _showCreateFolder.asStateFlow()

    private val _selectedBookmarkIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBookmarkIds: StateFlow<Set<Long>> = _selectedBookmarkIds.asStateFlow()

    private val _selectedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFolderIds: StateFlow<Set<Long>> = _selectedFolderIds.asStateFlow()

    private val _showMoveSelection = MutableStateFlow(false)
    val showMoveSelection: StateFlow<Boolean> = _showMoveSelection.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query.take(MAX_SEARCH_LENGTH)
    }

    fun setSort(value: BookmarkSort) {
        sort.value = value
    }

    fun setViewMode(value: BookmarkViewMode) {
        viewMode.value = value
    }

    fun openFolder(folder: BookmarkFolder) {
        clearSelection()
        _folderStack.value = _folderStack.value + folder
        _searchQuery.value = ""
    }

    /** Returns true when selection or a folder level was closed, false at the root. */
    fun navigateUp(): Boolean {
        if (hasSelection()) {
            clearSelection()
            return true
        }
        if (_folderStack.value.isEmpty()) return false
        _folderStack.value = _folderStack.value.dropLast(1)
        _searchQuery.value = ""
        return true
    }

    fun toggleBookmarkSelection(id: Long) {
        _selectedBookmarkIds.value = _selectedBookmarkIds.value.toggle(id)
    }

    fun toggleFolderSelection(id: Long) {
        _selectedFolderIds.value = _selectedFolderIds.value.toggle(id)
    }

    fun selectBookmark(id: Long) {
        _selectedBookmarkIds.value = _selectedBookmarkIds.value + id
    }

    fun selectFolder(id: Long) {
        _selectedFolderIds.value = _selectedFolderIds.value + id
    }

    fun selectAllVisible() {
        _selectedBookmarkIds.value = bookmarks.value.mapTo(mutableSetOf()) { it.id }
        _selectedFolderIds.value = if (_searchQuery.value.isBlank()) {
            folders.value.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
    }

    fun clearSelection() {
        _selectedBookmarkIds.value = emptySet()
        _selectedFolderIds.value = emptySet()
        _showMoveSelection.value = false
    }

    fun requestMoveSelection() {
        if (hasSelection()) _showMoveSelection.value = true
    }

    fun dismissMoveSelection() {
        _showMoveSelection.value = false
    }

    fun moveSelectionTo(folderId: Long?) {
        val bookmarkIds = _selectedBookmarkIds.value
        val folderIds = _selectedFolderIds.value
        if (folderId != null && folderId in folderIds) return
        _showMoveSelection.value = false
        clearSelection()
        viewModelScope.launch {
            mutationMutex.withLock {
                bookmarkIds.forEach { repository.moveBookmark(it, folderId) }
                folderIds.forEach { repository.moveFolder(it, folderId) }
            }
        }
    }

    fun deleteSelection() {
        val selectedBookmarks = bookmarks.value.filter { it.id in _selectedBookmarkIds.value }
        val selectedFolders = folders.value.filter { it.id in _selectedFolderIds.value }
        if (selectedBookmarks.isEmpty() && selectedFolders.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            mutationMutex.withLock {
                selectedBookmarks.forEach { repository.delete(it.id) }
                val deletedFolders = selectedFolders.map { repository.deleteFolder(it) }
                appendUndoState(BookmarkUndoState(selectedBookmarks, deletedFolders))
            }
        }
    }

    fun requestCreateFolder() {
        _showCreateFolder.value = true
    }

    fun dismissCreateFolder() {
        _showCreateFolder.value = false
    }

    fun createFolder(title: String) {
        val safeTitle = title.trim().limitBookmarkTitle()
        if (safeTitle.isEmpty()) return
        _showCreateFolder.value = false
        viewModelScope.launch {
            mutationMutex.withLock {
                repository.createFolder(safeTitle, currentFolder.value?.id)
            }
        }
    }

    fun delete(item: BookmarkItem) {
        viewModelScope.launch {
            mutationMutex.withLock {
                repository.delete(item.id)
                appendUndoState(BookmarkUndoState(bookmarks = listOf(item)))
            }
        }
    }

    fun deleteFolder(folder: BookmarkFolder) {
        viewModelScope.launch {
            mutationMutex.withLock {
                appendUndoState(
                    BookmarkUndoState(folders = listOf(repository.deleteFolder(folder)))
                )
            }
        }
    }

    fun undo() {
        val state = _undoState.value
        if (state.isEmpty) return
        _undoState.value = BookmarkUndoState()
        viewModelScope.launch {
            mutationMutex.withLock {
                state.bookmarks.forEach { repository.reinsert(it) }
                repository.restoreDeletedFolders(state.folders)
            }
        }
    }

    fun dismissUndo() {
        _undoState.value = BookmarkUndoState()
    }

    fun startEdit(item: BookmarkItem) {
        _editError.value = null
        _editingFolder.value = null
        _editingItem.value = item
    }

    fun startEditFolder(folder: BookmarkFolder) {
        _editError.value = null
        _editingItem.value = null
        _editingFolder.value = folder
    }

    fun dismissEdit() {
        _editingItem.value = null
        _editingFolder.value = null
        _editError.value = null
    }

    /** Atomically confirms an existing bookmark edit, including an optional folder move. */
    fun confirmEdit(title: String, url: String, folderId: Long?) {
        val item = _editingItem.value ?: return
        val safeTitle = title.trim().limitBookmarkTitle()
        val safeUrl = url.trim()
        if (safeTitle.isEmpty() || safeUrl.isEmpty()) {
            _editError.value = BookmarkEditError.INVALID_INPUT
            return
        }
        _editError.value = null
        viewModelScope.launch {
            mutationMutex.withLock {
                when (repository.updateBookmark(item.id, safeTitle, safeUrl, folderId)) {
                    BookmarkUpdateResult.UPDATED -> {
                        if (_editingItem.value?.id == item.id) _editingItem.value = null
                    }
                    BookmarkUpdateResult.INVALID_INPUT -> if (_editingItem.value?.id == item.id) {
                        _editError.value = BookmarkEditError.INVALID_INPUT
                    }
                    BookmarkUpdateResult.NOT_FOUND -> if (_editingItem.value?.id == item.id) {
                        _editError.value = BookmarkEditError.BOOKMARK_NOT_FOUND
                    }
                    BookmarkUpdateResult.URL_ALREADY_EXISTS -> if (_editingItem.value?.id == item.id) {
                        _editError.value = BookmarkEditError.URL_ALREADY_EXISTS
                    }
                }
            }
        }
    }

    fun confirmFolderRename(newTitle: String) {
        val folder = _editingFolder.value ?: return
        val title = newTitle.trim().limitBookmarkTitle()
        if (title.isEmpty()) {
            _editError.value = BookmarkEditError.INVALID_INPUT
            return
        }
        _editError.value = null
        viewModelScope.launch {
            mutationMutex.withLock {
                repository.renameFolder(folder.id, title)
                if (_editingFolder.value?.id == folder.id) _editingFolder.value = null
            }
        }
    }

    /** Compatibility for the current title-only dialog; full bookmark editors use the overload. */
    fun confirmEdit(newTitle: String) {
        val item = _editingItem.value
        if (item != null) {
            confirmEdit(newTitle, item.url, item.folderId)
        } else {
            confirmFolderRename(newTitle)
        }
    }

    fun requestMove(item: BookmarkItem) {
        _movingItem.value = item
    }

    fun dismissMove() {
        _movingItem.value = null
    }

    fun moveTo(folderId: Long?) {
        val item = _movingItem.value ?: return
        _movingItem.value = null
        viewModelScope.launch {
            mutationMutex.withLock { repository.moveBookmark(item.id, folderId) }
        }
    }

    fun openBookmark(item: BookmarkItem) {
        viewModelScope.launch { repository.markOpened(item.id) }
    }

    fun moveInManualOrder(item: BookmarkItem, offset: Int) {
        moveSiblingByOffset(id = item.id, isFolder = false, offset = offset)
    }

    fun moveFolderInManualOrder(folder: BookmarkFolder, offset: Int) {
        moveSiblingByOffset(id = folder.id, isFolder = true, offset = offset)
    }

    fun moveSiblingToIndex(id: Long, isFolder: Boolean, targetIndex: Int) {
        if (sort.value != BookmarkSort.Manual || _searchQuery.value.isNotBlank()) return
        val siblings = manualSiblings()
        val currentIndex = siblings.indexOfFirst { it.id == id && it.isFolder == isFolder }
        if (currentIndex < 0 || targetIndex !in siblings.indices || targetIndex == currentIndex) return
        val parentId = currentFolder.value?.id
        viewModelScope.launch {
            mutationMutex.withLock {
                repository.moveSiblingToIndex(id, isFolder, parentId, targetIndex)
            }
        }
    }

    private fun moveSiblingByOffset(id: Long, isFolder: Boolean, offset: Int) {
        if (sort.value != BookmarkSort.Manual || offset == 0) return
        val siblings = manualSiblings()
        val currentIndex = siblings.indexOfFirst { it.id == id && it.isFolder == isFolder }
        if (currentIndex < 0) return
        moveSiblingToIndex(id, isFolder, currentIndex + offset)
    }

    private fun manualSiblings(): List<ManualSibling> = buildList {
        folders.value.forEach { add(ManualSibling(it.id, isFolder = true, it.position)) }
        bookmarks.value.forEach { add(ManualSibling(it.id, isFolder = false, it.position)) }
    }.sortedWith(
        compareBy<ManualSibling> { it.position }
            .thenByDescending { it.isFolder }
            .thenBy { it.id },
    )

    private fun appendUndoState(deleted: BookmarkUndoState) {
        val pending = _undoState.value
        _undoState.value = BookmarkUndoState(
            bookmarks = (pending.bookmarks + deleted.bookmarks).distinctBy { it.id },
            folders = (pending.folders + deleted.folders).distinctBy { it.folder.id },
        )
    }

    private fun hasSelection(): Boolean =
        _selectedBookmarkIds.value.isNotEmpty() || _selectedFolderIds.value.isNotEmpty()

    private fun Set<Long>.toggle(id: Long): Set<Long> =
        if (id in this) this - id else this + id

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val MAX_SEARCH_LENGTH = 256
    }
}

private data class ManualSibling(val id: Long, val isFolder: Boolean, val position: Long)

enum class BookmarkEditError {
    INVALID_INPUT,
    BOOKMARK_NOT_FOUND,
    URL_ALREADY_EXISTS,
}

data class BookmarkUndoState(
    val bookmarks: List<BookmarkItem> = emptyList(),
    val folders: List<DeletedBookmarkFolder> = emptyList(),
) {
    val count: Int get() = bookmarks.size + folders.size
    val isEmpty: Boolean get() = count == 0
}
