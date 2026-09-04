package com.elewashy.nexa.feature.history.presentation.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.util.UrlDisplay
import com.elewashy.nexa.feature.history.domain.model.HistoryItem
import com.elewashy.nexa.feature.history.presentation.HistoryViewModel
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppEmptyState
import com.elewashy.nexa.ui.components.common.AppSearchField
import com.elewashy.nexa.ui.components.common.AppSelectionIndicator
import com.elewashy.nexa.ui.components.common.AppSelectionTopAppBar
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.ui.components.common.SiteFavicon
import com.elewashy.nexa.ui.components.common.appSearchFieldPadding
import com.elewashy.nexa.ui.components.dialogs.ConfirmationDialog
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Delete
import com.elewashy.nexa.ui.icons.History
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.SelectAll
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HistoryRoute(
    onBackClick: () -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val pagingItems = viewModel.history.collectAsLazyPagingItems()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val undoItems by viewModel.undoItems.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionBusy by viewModel.selectionBusy.collectAsStateWithLifecycle()
    val showClearDialog by viewModel.showClearDialog.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedMessage = if (undoItems.size <= 1) {
        stringResource(R.string.history_entry_deleted)
    } else {
        pluralStringResource(R.plurals.history_entries_deleted, undoItems.size, undoItems.size)
    }
    val undoLabel = stringResource(R.string.undo)
    var showDeleteSelectionDialog by rememberSaveable { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    BackHandler(enabled = selectionMode, onBack = viewModel::clearSelection)

    LaunchedEffect(undoItems, deletedMessage, undoLabel) {
        if (undoItems.isEmpty()) return@LaunchedEffect
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

    if (showDeleteSelectionDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.delete_selected_items),
            message = AnnotatedString(stringResource(R.string.delete_selected_history_message)),
            positiveButtonText = stringResource(R.string.delete),
            negativeButtonText = stringResource(R.string.cancel),
            onPositiveClick = viewModel::deleteSelection,
            onNegativeClick = {},
            onDismiss = { showDeleteSelectionDialog = false },
        )
    }

    if (showClearDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.clear_history),
            message = AnnotatedString(stringResource(R.string.clear_history_message)),
            positiveButtonText = stringResource(R.string.yes),
            negativeButtonText = stringResource(R.string.no),
            onPositiveClick = viewModel::confirmClear,
            onNegativeClick = viewModel::dismissClear,
            onDismiss = viewModel::dismissClear,
        )
    }

    Scaffold(
        topBar = {
            HistoryTopBar(
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onClearHistory = viewModel::requestClear,
                onClosePage = onBackClick,
                selectionCount = selectedIds.size,
                selectionBusy = selectionBusy,
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAllMatching,
                onDeleteSelection = { showDeleteSelectionDialog = true },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        HistoryContent(
            pagingItems = pagingItems,
            searching = searchQuery.isNotBlank(),
            onOpenUrl = onOpenUrl,
            onDelete = viewModel::delete,
            selectedIds = selectedIds,
            onToggleSelection = viewModel::toggleSelection,
            onLongSelect = viewModel::select,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClosePage: () -> Unit,
    selectionCount: Int,
    selectionBusy: Boolean,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    Column {
        if (selectionCount > 0) {
            AppSelectionTopAppBar(
                selectedCount = selectionCount,
                onClearSelection = onClearSelection,
                actions = {
                    IconButton(onClick = onSelectAll, enabled = !selectionBusy) {
                        Icon(SelectAll, contentDescription = stringResource(R.string.select_all))
                    }
                    IconButton(onClick = onDeleteSelection, enabled = !selectionBusy) {
                        Icon(Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                actions = {
                    IconButton(onClick = onClosePage) {
                        Icon(Close, contentDescription = stringResource(R.string.close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
            AppSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = stringResource(R.string.history_search_hint),
                modifier = Modifier.appSearchFieldPadding(),
            )
            TextButton(
                onClick = onClearHistory,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = stringResource(R.string.delete_browsing_data),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun HistoryContent(
    pagingItems: LazyPagingItems<HistoryItem>,
    searching: Boolean,
    onOpenUrl: (String) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onLongSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val refreshState = pagingItems.loadState.refresh) {
        is LoadState.Loading -> LoadingState(modifier)
        is LoadState.Error -> ErrorState(
            modifier = modifier,
            onRetry = pagingItems::retry,
        )
        is LoadState.NotLoading -> {
            if (pagingItems.itemCount == 0) {
                HistoryEmptyState(searching = searching, modifier = modifier)
            } else {
                HistoryList(
                    pagingItems = pagingItems,
                    onOpenUrl = onOpenUrl,
                    onDelete = onDelete,
                    selectedIds = selectedIds,
                    onToggleSelection = onToggleSelection,
                    onLongSelect = onLongSelect,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun HistoryList(
    pagingItems: LazyPagingItems<HistoryItem>,
    onOpenUrl: (String) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onLongSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = adaptiveInfo.listMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.id },
                contentType = pagingItems.itemContentType(),
            ) { index ->
                val item = pagingItems[index] ?: return@items
                val previous = if (index > 0) pagingItems.peek(index - 1) else null
                Column(modifier = Modifier.animateItem()) {
                    if (previous == null || historyDate(previous.visitedAt) != historyDate(item.visitedAt)) {
                        HistoryDateHeader(item.visitedAt)
                    }
                    HistoryItemRow(
                        item = item,
                        selectionMode = selectedIds.isNotEmpty(),
                        selected = item.id in selectedIds,
                        onClick = { onOpenUrl(item.url) },
                        onToggleSelection = { onToggleSelection(item.id) },
                        onLongSelect = { onLongSelect(item.id) },
                        onDelete = { onDelete(item) },
                    )
                }
            }

            when (pagingItems.loadState.append) {
                is LoadState.Loading -> item(contentType = "append-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                is LoadState.Error -> item(contentType = "append-error") {
                    TextButton(
                        onClick = pagingItems::retry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is LoadState.NotLoading -> Unit
            }
        }
    }
}

@Composable
private fun HistoryDateHeader(timestamp: Long) {
    val date = historyDate(timestamp)
    val locale = LocalConfiguration.current.locales[0]
    val formatted = remember(date, locale) {
        LocalizedFormatters.mediumDate(date, locale)
    }
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(R.string.history_date_today, formatted)
        today.minusDays(1) -> stringResource(R.string.history_date_yesterday, formatted)
        else -> formatted
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun HistoryItemRow(
    item: HistoryItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val host = remember(item.url) { UrlDisplay.hostOrUrl(item.url) }
    val title = item.title.ifBlank { host }
    val selectLabel = stringResource(R.string.select)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                else Color.Transparent,
            )
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClickLabel = selectLabel,
                onLongClick = onLongSelect,
            )
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteFavicon(pageUrl = item.url, size = 32.dp)
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionMode) {
            AppSelectionIndicator(selected = selected, onClick = onToggleSelection)
        } else {
            IconButton(onClick = onDelete) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Close,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.history_load_error),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun HistoryEmptyState(
    searching: Boolean,
    modifier: Modifier = Modifier,
) {
    AppEmptyState(
        icon = if (searching) Search else History,
        title = stringResource(
            if (searching) R.string.history_no_results_title else R.string.history_empty_title
        ),
        description = stringResource(
            if (searching) R.string.history_no_results_desc else R.string.history_empty_desc
        ),
        modifier = modifier.fillMaxSize(),
    )
}

private fun historyDate(timestamp: Long): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
