package com.elewashy.nexa.feature.bookmarks.presentation.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.util.UrlDisplay
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkFolder
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkItem
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkSort
import com.elewashy.nexa.feature.bookmarks.domain.model.BookmarkViewMode
import com.elewashy.nexa.feature.bookmarks.domain.model.limitBookmarkTitle
import com.elewashy.nexa.feature.bookmarks.presentation.BookmarkEditError
import com.elewashy.nexa.feature.bookmarks.presentation.BookmarksViewModel
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppEmptyState
import com.elewashy.nexa.ui.components.common.AppSearchField
import com.elewashy.nexa.ui.components.common.appSearchFieldPadding
import com.elewashy.nexa.ui.components.common.AppSelectionTopAppBar
import com.elewashy.nexa.ui.components.common.AppOverflowMenu
import com.elewashy.nexa.ui.components.common.AppSelectionIndicator
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.ui.components.common.SiteFavicon
import com.elewashy.nexa.ui.components.dialogs.ConfirmationDialog
import com.elewashy.nexa.ui.components.common.AppOverflowMenuItem
import com.elewashy.nexa.ui.icons.ArrowBack
import com.elewashy.nexa.ui.icons.Bookmarks
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.CreateNewFolder
import com.elewashy.nexa.ui.icons.Delete
import com.elewashy.nexa.ui.icons.FilterList
import com.elewashy.nexa.ui.icons.Folder
import com.elewashy.nexa.ui.icons.FolderOpen
import com.elewashy.nexa.ui.icons.MoreVert
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.SelectAll
import java.text.NumberFormat

@Composable
fun BookmarksRoute(
    onBackClick: () -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle()
    val selectedSort by viewModel.selectedSort.collectAsStateWithLifecycle()
    val viewMode by viewModel.selectedViewMode.collectAsStateWithLifecycle()
    val undoState by viewModel.undoState.collectAsStateWithLifecycle()
    val editingItem by viewModel.editingItem.collectAsStateWithLifecycle()
    val editingFolder by viewModel.editingFolder.collectAsStateWithLifecycle()
    val editError by viewModel.editError.collectAsStateWithLifecycle()
    val destinationFolders by viewModel.destinationFolders.collectAsStateWithLifecycle()
    val movingItem by viewModel.movingItem.collectAsStateWithLifecycle()
    val showCreateFolder by viewModel.showCreateFolder.collectAsStateWithLifecycle()
    val selectedBookmarkIds by viewModel.selectedBookmarkIds.collectAsStateWithLifecycle()
    val selectedFolderIds by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val showMoveSelection by viewModel.showMoveSelection.collectAsStateWithLifecycle()
    val selectionCount = selectedBookmarkIds.size + selectedFolderIds.size
    val selectionMode = selectionCount > 0
    var showDeleteSelectionDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val searching = searchQuery.isNotBlank()
    val visibleEntries = remember(folders, bookmarks, searching, selectedSort) {
        val folderEntries = if (searching) emptyList() else folders.map(BookmarkListEntry::FolderEntry)
        val bookmarkEntries = bookmarks.map(BookmarkListEntry::BookmarkEntry)
        if (selectedSort == BookmarkSort.Manual && !searching) {
            (folderEntries + bookmarkEntries).sortedWith(
                compareBy<BookmarkListEntry> { it.position }
                    .thenByDescending { it.isFolder }
                    .thenBy { it.id },
            )
        } else {
            folderEntries + bookmarkEntries
        }
    }
    val reorder = remember { IconReorderState<BookmarkListEntry> { it.stableId } }
    val rowExtentPx = with(LocalDensity.current) {
        (if (viewMode == BookmarkViewMode.Visual) 96.dp else 64.dp).toPx()
    }
    val edgeScrollThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val edgeScrollStepPx = with(LocalDensity.current) { 8.dp.toPx() }
    val reorderEnabled = selectedSort == BookmarkSort.Manual && !searching && !selectionMode

    LaunchedEffect(visibleEntries) { reorder.sync(visibleEntries) }
    LaunchedEffect(reorderEnabled) {
        if (!reorderEnabled) reorder.cancel(visibleEntries)
    }
    LaunchedEffect(reorder.draggedId, rowExtentPx) {
        while (reorder.draggedId != null) {
            val draggedEntry = reorder.items.firstOrNull { it.stableId == reorder.draggedId }
            val itemInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == draggedEntry?.lazyKey }
            val center = itemInfo?.let { it.offset + it.size / 2f + reorder.dragOffsetPx }
            val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
            val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
            val scroll = when {
                center == null -> 0f
                center < viewportStart + edgeScrollThresholdPx -> -edgeScrollStepPx
                center > viewportEnd - edgeScrollThresholdPx -> edgeScrollStepPx
                else -> 0f
            }
            if (scroll != 0f) {
                val consumed = listState.scrollBy(scroll)
                // Content moves opposite to scroll; compensate to keep the dragged row under the finger.
                reorder.dragBy(consumed, rowExtentPx)
            }
            withFrameNanos { }
        }
    }
    val deletedMessage = pluralStringResource(
        R.plurals.bookmarks_deleted,
        undoState.count.coerceAtLeast(1),
        undoState.count.coerceAtLeast(1),
    )
    val undoLabel = stringResource(R.string.undo)

    fun navigateBack() {
        if (!viewModel.navigateUp()) onBackClick()
    }
    BackHandler(onBack = ::navigateBack)

    LaunchedEffect(undoState, deletedMessage, undoLabel) {
        if (undoState.isEmpty) return@LaunchedEffect
        when (
            snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        ) {
            SnackbarResult.ActionPerformed -> viewModel.undo()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    if (showCreateFolder) {
        TitleDialog(
            title = stringResource(R.string.create_new_folder),
            initialValue = "",
            confirmLabel = stringResource(R.string.add),
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::dismissCreateFolder,
        )
    }
    editingItem?.let { item ->
        BookmarkEditDialog(
            item = item,
            folders = destinationFolders,
            error = editError,
            onConfirm = viewModel::confirmEdit,
            onDismiss = viewModel::dismissEdit,
        )
    }
    editingFolder?.let { folder ->
        TitleDialog(
            title = stringResource(R.string.rename_folder),
            initialValue = folder.title,
            confirmLabel = stringResource(R.string.save),
            onConfirm = viewModel::confirmFolderRename,
            onDismiss = viewModel::dismissEdit,
        )
    }
    movingItem?.let {
        MoveBookmarkDialog(
            folders = folders,
            currentFolder = currentFolder,
            onMove = viewModel::moveTo,
            onDismiss = viewModel::dismissMove,
        )
    }
    if (showMoveSelection) {
        MoveBookmarkDialog(
            folders = folders.filterNot { it.id in selectedFolderIds },
            currentFolder = currentFolder,
            onMove = viewModel::moveSelectionTo,
            onDismiss = viewModel::dismissMoveSelection,
        )
    }
    if (showDeleteSelectionDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.delete_selected_items),
            message = AnnotatedString(stringResource(R.string.delete_selected_items_message)),
            positiveButtonText = stringResource(R.string.delete),
            negativeButtonText = stringResource(R.string.cancel),
            onPositiveClick = viewModel::deleteSelection,
            onNegativeClick = {},
            onDismiss = { showDeleteSelectionDialog = false },
        )
    }

    Scaffold(
        topBar = {
            BookmarksHeader(
                title = currentFolder?.title ?: stringResource(R.string.bookmarks),
                inFolder = currentFolder != null,
                query = searchQuery,
                selectedSort = selectedSort,
                viewMode = viewMode,
                onBack = ::navigateBack,
                onClose = onBackClick,
                onQueryChange = viewModel::onSearchQueryChange,
                onSortChange = viewModel::setSort,
                onViewModeChange = viewModel::setViewMode,
                onCreateFolder = viewModel::requestCreateFolder,
                selectionCount = selectionCount,
                canMoveSelection = currentFolder != null || folders.any { it.id !in selectedFolderIds },
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAllVisible,
                onMoveSelection = viewModel::requestMoveSelection,
                onDeleteSelection = { showDeleteSelectionDialog = true },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (bookmarks.isEmpty() && (folders.isEmpty() || searching)) {
            BookmarksEmptyState(searching = searching, modifier = Modifier.padding(padding))
        } else {
            val adaptive = rememberAdaptiveLayoutInfo()
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = adaptive.listMaxWidth).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(
                        items = reorder.items,
                        key = { _, entry -> entry.lazyKey },
                        contentType = { _, entry -> if (entry.isFolder) "folder" else "bookmark" },
                    ) { index, entry ->
                        val dragging = reorder.isDragging(entry.stableId)
                        Box(
                            modifier = if (dragging) {
                                Modifier.zIndex(1f).graphicsLayer {
                                    translationY = reorder.dragOffsetPx
                                }
                            } else {
                                Modifier.animateItem()
                            },
                        ) {
                            when (entry) {
                                is BookmarkListEntry.FolderEntry -> {
                                    val folder = entry.folder
                                    FolderRow(
                                        folder = folder,
                                        visual = viewMode == BookmarkViewMode.Visual,
                                        isRootFolder = currentFolder == null,
                                        manualOrder = selectedSort == BookmarkSort.Manual,
                                        reorderEnabled = reorderEnabled,
                                        reorderDragging = dragging,
                                        onReorderStart = { reorder.start(entry.stableId) },
                                        onReorderDrag = { reorder.dragBy(it, rowExtentPx) },
                                        onReorderEnd = {
                                            reorder.finish()?.let { targetIndex ->
                                                viewModel.moveSiblingToIndex(
                                                    folder.id,
                                                    isFolder = true,
                                                    targetIndex = targetIndex,
                                                )
                                            }
                                        },
                                        onReorderCancel = { reorder.cancel(visibleEntries) },
                                        selectionMode = selectionMode,
                                        selected = folder.id in selectedFolderIds,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < reorder.items.lastIndex,
                                        onOpen = { viewModel.openFolder(folder) },
                                        onSelect = { viewModel.toggleFolderSelection(folder.id) },
                                        onLongSelect = { viewModel.selectFolder(folder.id) },
                                        onEdit = { viewModel.startEditFolder(folder) },
                                        onMoveUp = { viewModel.moveFolderInManualOrder(folder, -1) },
                                        onMoveDown = { viewModel.moveFolderInManualOrder(folder, 1) },
                                        onDelete = { viewModel.deleteFolder(folder) },
                                    )
                                }
                                is BookmarkListEntry.BookmarkEntry -> {
                                    val item = entry.bookmark
                                    BookmarkRow(
                                        item = item,
                                        visual = viewMode == BookmarkViewMode.Visual,
                                        manualOrder = selectedSort == BookmarkSort.Manual,
                                        reorderEnabled = reorderEnabled,
                                        reorderDragging = dragging,
                                        onReorderStart = { reorder.start(entry.stableId) },
                                        onReorderDrag = { reorder.dragBy(it, rowExtentPx) },
                                        onReorderEnd = {
                                            reorder.finish()?.let { targetIndex ->
                                                viewModel.moveSiblingToIndex(
                                                    item.id,
                                                    isFolder = false,
                                                    targetIndex = targetIndex,
                                                )
                                            }
                                        },
                                        onReorderCancel = { reorder.cancel(visibleEntries) },
                                        selectionMode = selectionMode,
                                        selected = item.id in selectedBookmarkIds,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < reorder.items.lastIndex,
                                        onOpen = {
                                            viewModel.openBookmark(item)
                                            onOpenUrl(item.url)
                                        },
                                        onSelect = { viewModel.toggleBookmarkSelection(item.id) },
                                        onLongSelect = { viewModel.selectBookmark(item.id) },
                                        onEdit = { viewModel.startEdit(item) },
                                        onMove = { viewModel.requestMove(item) },
                                        onMoveUp = { viewModel.moveInManualOrder(item, -1) },
                                        onMoveDown = { viewModel.moveInManualOrder(item, 1) },
                                        onDelete = { viewModel.delete(item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksHeader(
    title: String,
    inFolder: Boolean,
    query: String,
    selectedSort: BookmarkSort,
    viewMode: BookmarkViewMode,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (BookmarkSort) -> Unit,
    onViewModeChange: (BookmarkViewMode) -> Unit,
    onCreateFolder: () -> Unit,
    selectionCount: Int,
    canMoveSelection: Boolean,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    Column {
        if (selectionCount > 0) {
            AppSelectionTopAppBar(
                selectedCount = selectionCount,
                onClearSelection = onClearSelection,
                actions = {
                    IconButton(onClick = onSelectAll) {
                        Icon(SelectAll, contentDescription = stringResource(R.string.select_all))
                    }
                    IconButton(onClick = onMoveSelection, enabled = canMoveSelection) {
                        Icon(FolderOpen, contentDescription = stringResource(R.string.move_to))
                    }
                    IconButton(onClick = onDeleteSelection) {
                        Icon(Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        } else {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (inFolder) {
                        IconButton(onClick = onBack) {
                            Icon(ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortExpanded = true }) {
                            Icon(FilterList, contentDescription = stringResource(R.string.sort_and_view))
                        }
                        SortAndViewMenu(
                            expanded = sortExpanded,
                            selectedSort = selectedSort,
                            viewMode = viewMode,
                            onDismiss = { sortExpanded = false },
                            onSortChange = { onSortChange(it); sortExpanded = false },
                            onViewModeChange = { onViewModeChange(it); sortExpanded = false },
                        )
                    }
                    IconButton(onClick = onCreateFolder) {
                        Icon(CreateNewFolder, contentDescription = stringResource(R.string.create_new_folder))
                    }
                    IconButton(onClick = onClose) {
                        Icon(Close, contentDescription = stringResource(R.string.close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
        if (selectionCount == 0) {
            AppSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.bookmarks_search_hint),
                modifier = Modifier.appSearchFieldPadding(),
            )
        }
    }
}

@Composable
private fun SortAndViewMenu(
    expanded: Boolean,
    selectedSort: BookmarkSort,
    viewMode: BookmarkViewMode,
    onDismiss: () -> Unit,
    onSortChange: (BookmarkSort) -> Unit,
    onViewModeChange: (BookmarkViewMode) -> Unit,
) {
    AppOverflowMenu(expanded = expanded, onDismissRequest = onDismiss) {
        BookmarkSort.entries.forEach { sort ->
            AppOverflowMenuItem(
                text = sort.label(),
                trailingIcon = {
                    if (sort == selectedSort) Icon(Check, contentDescription = null)
                },
                onClick = { onSortChange(sort) },
            )
        }
        HorizontalDivider()
        BookmarkViewMode.entries.forEach { mode ->
            AppOverflowMenuItem(
                text = stringResource(
                    if (mode == BookmarkViewMode.Visual) R.string.visual_view else R.string.compact_view
                ),
                trailingIcon = {
                    if (mode == viewMode) Icon(Check, contentDescription = null)
                },
                onClick = { onViewModeChange(mode) },
            )
        }
    }
}

@Composable
private fun BookmarkSort.label(): String = stringResource(
    when (this) {
        BookmarkSort.Manual -> R.string.sort_manual
        BookmarkSort.Newest -> R.string.sort_newest
        BookmarkSort.Oldest -> R.string.sort_oldest
        BookmarkSort.LastOpened -> R.string.sort_last_opened
        BookmarkSort.TitleAscending -> R.string.sort_a_to_z
        BookmarkSort.TitleDescending -> R.string.sort_z_to_a
    }
)

@Composable
private fun FolderRow(
    folder: BookmarkFolder,
    visual: Boolean,
    isRootFolder: Boolean,
    manualOrder: Boolean,
    reorderEnabled: Boolean,
    reorderDragging: Boolean,
    onReorderStart: () -> Unit,
    onReorderDrag: (Float) -> Unit,
    onReorderEnd: () -> Unit,
    onReorderCancel: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onLongSelect: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    BookmarkBaseRow(
        visual = visual,
        leading = {
            ReorderableLeading(
                enabled = reorderEnabled,
                dragging = reorderDragging,
                onDragStart = onReorderStart,
                onDrag = onReorderDrag,
                onDragEnd = onReorderEnd,
                onDragCancel = onReorderCancel,
                onClick = { if (selectionMode) onSelect() else onOpen() },
                onLongClick = onLongSelect,
            ) {
                FolderTile(
                    itemCount = folder.itemCount,
                    visual = visual,
                    highlighted = isRootFolder,
                )
            }
        },
        title = folder.title,
        subtitle = null,
        selectionMode = selectionMode,
        selected = selected,
        onOpen = onOpen,
        onSelect = onSelect,
        onLongSelect = onLongSelect,
        menu = {
            ItemMenu(
                onSelect = onLongSelect,
                onEdit = onEdit,
                onMove = null,
                onMoveUp = onMoveUp.takeIf { manualOrder && canMoveUp },
                onMoveDown = onMoveDown.takeIf { manualOrder && canMoveDown },
                onDelete = onDelete,
            )
        },
    )
}

@Composable
private fun ReorderableLeading(
    enabled: Boolean,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                val scale = if (dragging) 1.04f else 1f
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClick = onClick)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnDragStart()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    currentOnDrag(amount.y)
                                },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragCancel() },
                            )
                        }
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun FolderTile(
    itemCount: Int,
    visual: Boolean,
    highlighted: Boolean,
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val size = if (visual) 80.dp else 44.dp
    val locale = LocalConfiguration.current.locales[0]
    val formattedCount = remember(itemCount, locale) {
        NumberFormat.getIntegerInstance(locale).format(itemCount.coerceAtLeast(0))
    }
    Column(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (visual) 20.dp else 12.dp))
            .background(containerColor)
            .padding(
                start = if (visual) 12.dp else 5.dp,
                top = if (visual) 16.dp else 4.dp,
                end = if (visual) 10.dp else 5.dp,
                bottom = if (visual) 8.dp else 3.dp,
            ),
    ) {
        Icon(
            imageVector = Folder,
            contentDescription = null,
            modifier = Modifier
                .size(if (visual) 28.dp else 20.dp)
                .align(Alignment.Start),
            tint = contentColor,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formattedCount,
            modifier = Modifier.align(Alignment.End),
            color = contentColor,
            style = if (visual) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun BookmarkRow(
    item: BookmarkItem,
    visual: Boolean,
    manualOrder: Boolean,
    reorderEnabled: Boolean,
    reorderDragging: Boolean,
    onReorderStart: () -> Unit,
    onReorderDrag: (Float) -> Unit,
    onReorderEnd: () -> Unit,
    onReorderCancel: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onLongSelect: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val host = remember(item.url) { UrlDisplay.hostOrUrl(item.url) }
    BookmarkBaseRow(
        visual = visual,
        leading = {
            ReorderableLeading(
                enabled = reorderEnabled,
                dragging = reorderDragging,
                onDragStart = onReorderStart,
                onDrag = onReorderDrag,
                onDragEnd = onReorderEnd,
                onDragCancel = onReorderCancel,
                onClick = { if (selectionMode) onSelect() else onOpen() },
                onLongClick = onLongSelect,
            ) {
                BookmarkTile(visual = visual) {
                    SiteFavicon(
                        pageUrl = item.url,
                        size = if (visual) 32.dp else 22.dp,
                    )
                }
            }
        },
        title = item.title.ifBlank { host },
        subtitle = host,
        selectionMode = selectionMode,
        selected = selected,
        onOpen = onOpen,
        onSelect = onSelect,
        onLongSelect = onLongSelect,
        menu = {
            ItemMenu(
                onSelect = onLongSelect,
                onEdit = onEdit,
                onMove = onMove,
                onMoveUp = onMoveUp.takeIf { manualOrder && canMoveUp },
                onMoveDown = onMoveDown.takeIf { manualOrder && canMoveDown },
                onDelete = onDelete,
            )
        },
    )
}

@Composable
private fun BookmarkBaseRow(
    visual: Boolean,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onLongSelect: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (visual) 96.dp else 64.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                else Color.Transparent
            )
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keep the icon outside the row's selection gesture region. Its long press is reserved
        // for reordering while the title/subtitle/menu side retains normal long-press selection.
        leading()
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = if (visual) 96.dp else 64.dp)
                .combinedClickable(
                    onClick = { if (selectionMode) onSelect() else onOpen() },
                    onLongClick = onLongSelect,
                )
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    style = (if (visual) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    }),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selectionMode) {
                AppSelectionIndicator(selected = selected, onClick = onSelect)
            } else {
                menu()
            }
        }
    }
}

@Composable
private fun BookmarkTile(visual: Boolean, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(if (visual) 72.dp else 40.dp)
            .clip(RoundedCornerShape(if (visual) 20.dp else 12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun ItemMenu(
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onMove: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        AppOverflowMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppOverflowMenuItem(
                text = stringResource(R.string.select),
                onClick = { expanded = false; onSelect() },
            )
            AppOverflowMenuItem(
                text = stringResource(R.string.edit),
                onClick = { expanded = false; onEdit() },
            )
            onMove?.let { move ->
                AppOverflowMenuItem(
                    text = stringResource(R.string.move_to),
                    onClick = { expanded = false; move() },
                )
            }
            onMoveUp?.let { moveUp ->
                AppOverflowMenuItem(
                    text = stringResource(R.string.move_up),
                    onClick = { expanded = false; moveUp() },
                )
            }
            onMoveDown?.let { moveDown ->
                AppOverflowMenuItem(
                    text = stringResource(R.string.move_down),
                    onClick = { expanded = false; moveDown() },
                )
            }
            AppOverflowMenuItem(
                text = stringResource(R.string.delete),
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun BookmarkEditDialog(
    item: BookmarkItem,
    folders: List<BookmarkFolder>,
    error: BookmarkEditError?,
    onConfirm: (title: String, url: String, folderId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    var url by remember(item.id) { mutableStateOf(item.url) }
    var folderId by remember(item.id) { mutableStateOf(item.folderId) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val selectedFolder = folders.firstOrNull { it.id == folderId }
    val canConfirm = title.isNotBlank() && url.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp)) {
                Text(
                    text = stringResource(R.string.edit_bookmark),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextField(
                    value = title,
                    onValueChange = { title = it.limitBookmarkTitle() },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    isError = error == BookmarkEditError.INVALID_INPUT,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Start),
                )
                TextField(
                    value = url,
                    onValueChange = { url = it.take(MAX_BOOKMARK_URL_LENGTH) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text(stringResource(R.string.web_address)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canConfirm) onConfirm(title, url, folderId) },
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        textAlign = TextAlign.Start,
                        textDirection = TextDirection.Ltr,
                    ),
                    supportingText = error?.let {
                        {
                            Text(
                                stringResource(
                                    when (it) {
                                        BookmarkEditError.INVALID_INPUT -> R.string.bookmark_edit_invalid
                                        BookmarkEditError.BOOKMARK_NOT_FOUND -> R.string.bookmark_edit_not_found
                                        BookmarkEditError.URL_ALREADY_EXISTS -> R.string.bookmark_url_exists
                                    }
                                )
                            )
                        }
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    TextButton(
                        onClick = { folderMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.bookmark_location_value,
                                selectedFolder?.title ?: stringResource(R.string.bookmarks_root),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                    AppOverflowMenu(
                        expanded = folderMenuExpanded,
                        onDismissRequest = { folderMenuExpanded = false },
                    ) {
                        AppOverflowMenuItem(
                            text = stringResource(R.string.bookmarks_root),
                            trailingIcon = { if (folderId == null) Icon(Check, contentDescription = null) },
                            onClick = { folderId = null; folderMenuExpanded = false },
                        )
                        folders.forEach { folder ->
                            AppOverflowMenuItem(
                                text = folder.title,
                                trailingIcon = {
                                    if (folder.id == folderId) Icon(Check, contentDescription = null)
                                },
                                onClick = { folderId = folder.id; folderMenuExpanded = false },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { onConfirm(title, url, folderId) },
                        enabled = canConfirm,
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

@Composable
private fun TitleDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canConfirm = value.isNotBlank()
    fun confirm() {
        if (canConfirm) onConfirm(value)
    }

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextField(
                    value = value,
                    onValueChange = { value = it.limitBookmarkTitle() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp)
                        .focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Start),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = ::confirm,
                        enabled = canConfirm,
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveBookmarkDialog(
    folders: List<BookmarkFolder>,
    currentFolder: BookmarkFolder?,
    onMove: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    TextButton(
                        onClick = { onMove(null) },
                        enabled = currentFolder != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.bookmarks_root)) }
                }
                items(folders, key = { it.id }) { folder ->
                    TextButton(
                        onClick = { onMove(folder.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = folder.title,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun BookmarksEmptyState(searching: Boolean, modifier: Modifier = Modifier) {
    AppEmptyState(
        icon = if (searching) Search else Bookmarks,
        title = stringResource(
            if (searching) R.string.bookmarks_no_results else R.string.bookmarks_empty_title
        ),
        description = stringResource(
            if (searching) R.string.bookmarks_no_results_desc else R.string.bookmarks_empty_desc
        ),
        modifier = modifier.fillMaxSize(),
    )
}

private const val MAX_BOOKMARK_URL_LENGTH = 8_192

private sealed interface BookmarkListEntry {
    val id: Long
    val position: Long
    val isFolder: Boolean
    val stableId: Long
    val lazyKey: String

    data class FolderEntry(val folder: BookmarkFolder) : BookmarkListEntry {
        override val id: Long get() = folder.id
        override val position: Long get() = folder.position
        override val isFolder: Boolean get() = true
        override val stableId: Long get() = -folder.id
        override val lazyKey: String get() = "folder-${folder.id}"
    }

    data class BookmarkEntry(val bookmark: BookmarkItem) : BookmarkListEntry {
        override val id: Long get() = bookmark.id
        override val position: Long get() = bookmark.position
        override val isFolder: Boolean get() = false
        override val stableId: Long get() = bookmark.id
        override val lazyKey: String get() = "bookmark-${bookmark.id}"
    }
}

@Stable
private class IconReorderState<T>(private val idOf: (T) -> Long) {
    private val orderedItems = mutableStateListOf<T>()
    val items: List<T> get() = orderedItems

    var draggedId: Long? by mutableStateOf(null)
        private set
    var dragOffsetPx: Float by mutableFloatStateOf(0f)
        private set
    private var startIndex = -1

    fun sync(source: List<T>) {
        if (draggedId != null) return
        orderedItems.clear()
        orderedItems.addAll(source)
    }

    fun start(id: Long) {
        val index = orderedItems.indexOfFirst { idOf(it) == id }
        if (index < 0) return
        draggedId = id
        startIndex = index
        dragOffsetPx = 0f
    }

    fun isDragging(id: Long): Boolean = draggedId == id

    fun dragBy(deltaPx: Float, itemExtentPx: Float) {
        val id = draggedId ?: return
        if (!deltaPx.isFinite() || itemExtentPx <= 0f) return
        dragOffsetPx += deltaPx
        var index = orderedItems.indexOfFirst { idOf(it) == id }
        if (index < 0) return
        val threshold = itemExtentPx / 2f
        while (dragOffsetPx > threshold && index < orderedItems.lastIndex) {
            orderedItems.add(index + 1, orderedItems.removeAt(index))
            index += 1
            dragOffsetPx -= itemExtentPx
        }
        while (dragOffsetPx < -threshold && index > 0) {
            orderedItems.add(index - 1, orderedItems.removeAt(index))
            index -= 1
            dragOffsetPx += itemExtentPx
        }
    }

    /** Returns the new index only when the item actually changed position. */
    fun finish(): Int? {
        val id = draggedId ?: return null
        val targetIndex = orderedItems.indexOfFirst { idOf(it) == id }
        val changedIndex = targetIndex.takeIf { it >= 0 && it != startIndex }
        clearDrag()
        return changedIndex
    }

    fun cancel(source: List<T>) {
        clearDrag()
        orderedItems.clear()
        orderedItems.addAll(source)
    }

    private fun clearDrag() {
        draggedId = null
        dragOffsetPx = 0f
        startIndex = -1
    }
}
