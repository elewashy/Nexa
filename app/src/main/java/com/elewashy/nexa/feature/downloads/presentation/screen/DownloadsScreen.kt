package com.elewashy.nexa.feature.downloads.presentation.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadItemCard
import com.elewashy.nexa.ui.adaptive.adaptiveGridColumns
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.PagerSegmentedControl
import com.elewashy.nexa.ui.components.common.PillTab
import kotlinx.coroutines.launch
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.DeleteFilled
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.DownloadDone

private enum class DownloadsTab(val titleRes: Int, val icon: ImageVector) {
    Downloaders(R.string.downloads_tab_active, Download),
    Apps(R.string.downloads_tab_completed, DownloadDone)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    snackbarHostState: SnackbarHostState,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    onBackClick: () -> Unit,
    onItemClick: (DownloadItem) -> Unit,
    onItemLongClick: (DownloadItem) -> Unit,
    onPauseClick: (DownloadItem) -> Unit,
    onResumeClick: (DownloadItem) -> Unit,
    onCancelClick: (DownloadItem) -> Unit,
    onRetryClick: (DownloadItem) -> Unit,
    onOpenFileClick: (DownloadItem) -> Unit,
    onDeleteClick: (DownloadItem) -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val pagerState = rememberPagerState(pageCount = { DownloadsTab.entries.size })
    val scope = rememberCoroutineScope()

    val downloadersListState = rememberLazyListState()
    val appsListState = rememberLazyListState()
    val selectedListState = remember(pagerState.currentPage, downloadersListState, appsListState) {
        if (pagerState.currentPage == DownloadsTab.Downloaders.ordinal) downloadersListState else appsListState
    }

    val canScroll by remember(selectedListState) {
        derivedStateOf { selectedListState.canScrollBackward || selectedListState.canScrollForward }
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(canScroll = { canScroll })

    val activeDownloads = remember(downloads) {
        downloads.filter {
            it.status in setOf(
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.PENDING,
                DownloadStatus.FAILED
            )
        }
    }
    val completedDownloads = remember(downloads) {
        downloads.filter {
            it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED)
        }
    }

    val showDeleteAction = isMultiSelectMode && selectedItems.isNotEmpty()

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    if (isMultiSelectMode) {
                        Text(pluralStringResource(R.plurals.selected_count, selectedItems.size, selectedItems.size))
                    } else {
                        Text(stringResource(R.string.downloads))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (isMultiSelectMode) onClearSelection else onBackClick) {
                        Icon(
                            imageVector = ArrowBackFilled,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (showDeleteAction) {
                        IconButton(onClick = onDeleteSelected) {
                            Icon(
                                imageVector = DeleteFilled,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            ) { snackbarData ->
                Snackbar(
                    snackbarData,
                    shape = MaterialTheme.shapes.extraLarge,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                    dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PagerSegmentedControl(
                pagerState = pagerState,
                maxWidth = adaptiveInfo.listMaxWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = adaptiveInfo.horizontalPadding, vertical = 8.dp),
            ) {
                DownloadsTab.entries.forEachIndexed { index, tab ->
                    PillTab(
                        index = index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(stringResource(tab.titleRes)) },
                        icon = { Icon(tab.icon, contentDescription = null) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (DownloadsTab.entries[page]) {
                    DownloadsTab.Downloaders -> DownloadersTabContent(
                        items = activeDownloads,
                        selectedItems = selectedItems,
                        isMultiSelectMode = isMultiSelectMode,
                        onItemClick = onItemClick,
                        onItemLongClick = onItemLongClick,
                        onPauseClick = onPauseClick,
                        onResumeClick = onResumeClick,
                        onCancelClick = onCancelClick,
                        onRetryClick = onRetryClick,
                    )
                    DownloadsTab.Apps -> AppsTabContent(
                        items = completedDownloads,
                        selectedItems = selectedItems,
                        isMultiSelectMode = isMultiSelectMode,
                        onItemClick = onItemClick,
                        onItemLongClick = onItemLongClick,
                        onOpenFileClick = onOpenFileClick,
                        onRetryClick = onRetryClick,
                        onDeleteClick = onDeleteClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadersTabContent(
    items: List<DownloadItem>,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    onItemClick: (DownloadItem) -> Unit,
    onItemLongClick: (DownloadItem) -> Unit,
    onPauseClick: (DownloadItem) -> Unit,
    onResumeClick: (DownloadItem) -> Unit,
    onCancelClick: (DownloadItem) -> Unit,
    onRetryClick: (DownloadItem) -> Unit,
) {
    if (items.isEmpty()) {
        TabEmptyState(
            icon = Download,
            title = stringResource(R.string.downloads_empty_downloaders_title),
            description = stringResource(R.string.downloads_empty_downloaders_desc)
        )
    } else {
        AdaptiveDownloadsList(
            items = items,
            isMultiSelectMode = isMultiSelectMode,
            itemContent = { item ->
                DownloadItemCard(
                    item = item,
                    isSelected = selectedItems.contains(item.id),
                    isMultiSelectMode = isMultiSelectMode,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    onPauseClick = { onPauseClick(item) },
                    onResumeClick = { onResumeClick(item) },
                    onCancelClick = { onCancelClick(item) },
                    onRetryClick = { onRetryClick(item) },
                )
            }
        )
    }
}

@Composable
private fun AppsTabContent(
    items: List<DownloadItem>,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    onItemClick: (DownloadItem) -> Unit,
    onItemLongClick: (DownloadItem) -> Unit,
    onOpenFileClick: (DownloadItem) -> Unit,
    onRetryClick: (DownloadItem) -> Unit,
    onDeleteClick: (DownloadItem) -> Unit,
) {
    if (items.isEmpty()) {
        TabEmptyState(
            icon = DownloadDone,
            title = stringResource(R.string.downloads_empty_apps_title),
            description = stringResource(R.string.downloads_empty_apps_desc)
        )
    } else {
        AdaptiveDownloadsList(
            items = items,
            isMultiSelectMode = isMultiSelectMode,
            itemContent = { item ->
                DownloadItemCard(
                    item = item,
                    isSelected = selectedItems.contains(item.id),
                    isMultiSelectMode = isMultiSelectMode,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    onOpenFileClick = { onOpenFileClick(item) },
                    onRetryClick = { onRetryClick(item) },
                    onDeleteClick = { onDeleteClick(item) },
                )
            }
        )
    }
}

@Composable
private fun AdaptiveDownloadsList(
    items: List<DownloadItem>,
    isMultiSelectMode: Boolean,
    itemContent: @Composable (DownloadItem) -> Unit,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (adaptiveInfo.isExpanded && !isMultiSelectMode) {
            adaptiveGridColumns(maxWidth, adaptiveInfo.gridMinCellWidth, minColumns = 2, maxColumns = 3)
        } else {
            1
        }

        if (columns == 1) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = adaptiveInfo.listMaxWidth)
                        .fillMaxHeight()
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = adaptiveInfo.horizontalPadding,
                        vertical = 4.dp,
                    )
                ) {
                    items(items = items, key = { it.id }) { item -> itemContent(item) }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    horizontal = adaptiveInfo.horizontalPadding,
                    vertical = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(items = items, key = { it.id }) { item -> itemContent(item) }
            }
        }
    }
}

@Composable
private fun TabEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = adaptiveInfo.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = adaptiveInfo.contentMaxWidth)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(if (adaptiveInfo.isTvLike) 120.dp else 100.dp)
                    .alpha(0.6f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
