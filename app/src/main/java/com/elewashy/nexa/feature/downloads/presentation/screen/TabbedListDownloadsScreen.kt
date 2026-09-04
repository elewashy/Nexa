package com.elewashy.nexa.feature.downloads.presentation.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadItemActions
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadItemCard
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadManagerHeader
import com.elewashy.nexa.ui.adaptive.adaptiveGridColumns
import com.elewashy.nexa.ui.components.common.AppEmptyState
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.PagerSegmentedControl
import com.elewashy.nexa.ui.components.common.PillTab
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.DownloadDone
import com.elewashy.nexa.ui.icons.Search
import kotlinx.coroutines.launch

private enum class DownloadsTab(val titleRes: Int, val icon: ImageVector) {
    Active(R.string.downloads_tab_active, Download),
    Completed(R.string.downloads_tab_completed, DownloadDone)
}

/** Statuses shown on the in-progress tab; everything else belongs to the completed tab. */
private val ACTIVE_TAB_STATUSES = setOf(
    DownloadStatus.DOWNLOADING,
    DownloadStatus.PAUSED,
    DownloadStatus.PENDING,
    DownloadStatus.FAILED,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TabbedListDownloadsScreen(
    downloads: List<DownloadItem>,
    snackbarHostState: SnackbarHostState,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    actions: DownloadItemActions,
    visualVideoPresentation: Boolean,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val pagerState = rememberPagerState(pageCount = { DownloadsTab.entries.size })
    val scope = rememberCoroutineScope()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(downloads.isEmpty()) {
        if (downloads.isEmpty()) {
            searchVisible = false
            query = ""
        }
    }

    val activeListState = rememberLazyListState()
    val completedListState = rememberLazyListState()
    val visibleDownloads = remember(downloads, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) downloads else downloads.filter { item ->
            item.fileName.contains(normalizedQuery, ignoreCase = true) ||
                item.url.contains(normalizedQuery, ignoreCase = true)
        }
    }
    val (activeDownloads, completedDownloads) = remember(visibleDownloads) {
        visibleDownloads.partition { it.status in ACTIVE_TAB_STATUSES }
    }

    Scaffold(
        topBar = {
            DownloadManagerHeader(
                downloads = downloads,
                selectedCount = selectedItems.size,
                isMultiSelectMode = isMultiSelectMode,
                searchVisible = searchVisible,
                query = query,
                onQueryChange = { query = it },
                onSearchClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) query = ""
                },
                onSettingsClick = onSettingsClick,
                onDeleteSelected = onDeleteSelected,
                onCloseClick = if (isMultiSelectMode) onClearSelection else onBackClick,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
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
                    DownloadsTab.Active -> ActiveTabContent(
                        items = activeDownloads,
                        searching = query.isNotBlank(),
                        listState = activeListState,
                        selectedItems = selectedItems,
                        isMultiSelectMode = isMultiSelectMode,
                        actions = actions,
                    )
                    DownloadsTab.Completed -> CompletedTabContent(
                        items = completedDownloads,
                        searching = query.isNotBlank(),
                        listState = completedListState,
                        selectedItems = selectedItems,
                        isMultiSelectMode = isMultiSelectMode,
                        actions = actions,
                        visualVideoPresentation = visualVideoPresentation,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveTabContent(
    items: List<DownloadItem>,
    searching: Boolean,
    listState: LazyListState,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    actions: DownloadItemActions,
) {
    if (items.isEmpty()) {
        TabEmptyState(
            icon = if (searching) Search else Download,
            title = stringResource(
                if (searching) R.string.downloads_no_matches else R.string.downloads_empty_downloaders_title
            ),
            description = stringResource(
                if (searching) R.string.downloads_no_matches_desc else R.string.downloads_empty_downloaders_desc
            ),
        )
    } else {
        AdaptiveDownloadsList(
            items = items,
            listState = listState,
            isMultiSelectMode = isMultiSelectMode,
            itemContent = { item ->
                DownloadItemCard(
                    item = item,
                    isSelected = item.id in selectedItems,
                    isMultiSelectMode = isMultiSelectMode,
                    actions = actions,
                )
            }
        )
    }
}

@Composable
private fun CompletedTabContent(
    items: List<DownloadItem>,
    searching: Boolean,
    listState: LazyListState,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    actions: DownloadItemActions,
    visualVideoPresentation: Boolean,
) {
    if (items.isEmpty()) {
        TabEmptyState(
            icon = if (searching) Search else DownloadDone,
            title = stringResource(
                if (searching) R.string.downloads_no_matches else R.string.downloads_empty_apps_title
            ),
            description = stringResource(
                if (searching) R.string.downloads_no_matches_desc else R.string.downloads_empty_apps_desc
            ),
        )
    } else {
        AdaptiveDownloadsList(
            items = items,
            listState = listState,
            isMultiSelectMode = isMultiSelectMode,
            contentType = { item ->
                if (isFeaturedVideo(item, visualVideoPresentation)) "featured-video" else item.status
            },
            itemContent = { item ->
                if (isFeaturedVideo(item, visualVideoPresentation)) {
                    FeaturedVideoDownload(
                        item = item,
                        selected = item.id in selectedItems,
                        isMultiSelectMode = isMultiSelectMode,
                        actions = actions,
                    )
                } else {
                    DownloadItemCard(
                        item = item,
                        isSelected = item.id in selectedItems,
                        isMultiSelectMode = isMultiSelectMode,
                        actions = actions,
                    )
                }
            }
        )
    }
}

@Composable
private fun AdaptiveDownloadsList(
    items: List<DownloadItem>,
    listState: LazyListState,
    isMultiSelectMode: Boolean,
    contentType: (DownloadItem) -> Any = { it.status },
    itemContent: @Composable (DownloadItem) -> Unit,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (adaptiveInfo.isExpanded && !isMultiSelectMode) {
            adaptiveGridColumns(maxWidth, adaptiveInfo.gridMinCellWidth, minColumns = 2, maxColumns = 3)
        } else {
            1
        }

        // Scaffold's innerPadding already carries the navigation-bar inset, so the
        // lists below must not add it again.
        if (columns == 1) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = adaptiveInfo.listMaxWidth)
                        .fillMaxHeight()
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = adaptiveInfo.horizontalPadding,
                        vertical = 4.dp,
                    )
                ) {
                    items(
                        items = items,
                        key = { it.id },
                        contentType = contentType,
                    ) { item ->
                        Box(modifier = Modifier.animateItem()) { itemContent(item) }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = adaptiveInfo.horizontalPadding,
                    vertical = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(
                    items = items,
                    key = { it.id },
                    contentType = contentType,
                ) { item ->
                    Box(modifier = Modifier.animateItem()) { itemContent(item) }
                }
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
    AppEmptyState(
        icon = icon,
        title = title,
        description = description,
        modifier = Modifier.fillMaxSize(),
    )
}
