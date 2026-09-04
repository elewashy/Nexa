package com.elewashy.nexa.feature.browser.presentation.screen

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.elewashy.nexa.R
import com.elewashy.nexa.core.text.limitCodePoints
import com.elewashy.nexa.core.util.SafeUrls.isSafeLoadableUrl
import com.elewashy.nexa.core.util.UrlDisplay
import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppEmptyState
import com.elewashy.nexa.ui.components.common.AppOverflowMenu
import com.elewashy.nexa.ui.components.common.AppOverflowMenuItem
import com.elewashy.nexa.ui.components.common.AppSelectionIndicator
import com.elewashy.nexa.ui.components.common.AppSearchField
import com.elewashy.nexa.ui.components.common.AppSelectionTopAppBar
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.ui.components.common.AppTabCountIcon
import com.elewashy.nexa.ui.components.common.PillTab
import com.elewashy.nexa.ui.components.common.PillTabBar
import com.elewashy.nexa.ui.components.common.SiteFavicon
import com.elewashy.nexa.ui.icons.Add
import com.elewashy.nexa.ui.icons.Bookmark
import com.elewashy.nexa.ui.icons.BookmarkBorder
import com.elewashy.nexa.ui.icons.CheckCircle
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Incognito
import com.elewashy.nexa.ui.icons.MoreVert
import com.elewashy.nexa.ui.icons.PushPin
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.SelectAll
import com.elewashy.nexa.ui.icons.Share
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Adaptive tab overview modeled after Chrome's compact tab grid. Tab cards consume metadata and
 * bounded runtime images only; rendering this screen never materializes a WebView.
 */
@Composable
fun TabSwitcherSheet(
    tabs: List<TabItem>,
    activeTabId: Long?,
    bookmarkedUrls: Set<String>,
    privateBrowsingAvailable: Boolean,
    thumbnailFor: (Long) -> Bitmap?,
    runtimeFaviconFor: (Long) -> Bitmap?,
    onTabClick: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCloseSelectedTabs: (Set<Long>) -> Unit,
    onSetTabPinned: (Long, Boolean) -> Unit,
    onSetTabsPinned: (Set<Long>, Boolean) -> Unit,
    onReorderTab: (Long, Int) -> Unit,
    onBookmarkTab: (TabItem) -> Unit,
    onSetTabsBookmarked: (List<TabItem>, Boolean) -> Unit,
    onShareTab: (TabItem) -> Unit,
    onCloseTabs: (BrowsingMode) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onReopenTab: (TabItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val normalCount = tabs.count { !it.isPrivate }
    val privateCount = tabs.size - normalCount
    val privateTabsExist = privateCount > 0
    val initialMode = tabs.firstOrNull { it.id == activeTabId }?.browsingMode ?: BrowsingMode.Normal
    // Keep two logical pages for the lifetime of this overlay. Shrinking a Pager from page 1 to
    // one page while its last private card closes leaves a transient invalid current page and was
    // the source of the broken header restoration. The second page is non-scrollable while absent.
    val pagerState = rememberPagerState(
        initialPage = if (initialMode == BrowsingMode.Private && privateTabsExist) 1 else 0,
        pageCount = { WORKSPACE_PAGE_COUNT },
    )
    val showPrivateMode = shouldShowPrivateMode(privateCount, pagerState.currentPage)
    val mode = if (showPrivateMode && pagerState.currentPage == PRIVATE_PAGE) {
        BrowsingMode.Private
    } else {
        BrowsingMode.Normal
    }
    var query by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedTabIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionMode = selectedTabIds.isNotEmpty()
    val selectedTabs = tabs.filter { it.id in selectedTabIds }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.undo)

    fun toggleSelection(tabId: Long) {
        selectedTabIds = if (tabId in selectedTabIds) selectedTabIds - tabId else selectedTabIds + tabId
    }

    BackHandler {
        if (selectionMode) selectedTabIds = emptySet() else onDismiss()
    }
    LaunchedEffect(activeTabId, privateTabsExist) {
        val activePage = workspacePageFor(
            activeIsPrivate = tabs.firstOrNull { it.id == activeTabId }?.isPrivate == true,
            privateTabsExist = privateTabsExist,
        )
        if (pagerState.currentPage != activePage) pagerState.scrollToPage(activePage)
    }
    LaunchedEffect(mode) {
        query = ""
        selectedTabIds = emptySet()
    }
    LaunchedEffect(tabs) {
        selectedTabIds = selectedTabIds.intersect(tabs.mapTo(mutableSetOf()) { it.id })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { AppSnackbarHost(snackbarHostState, navigationBarPadding = false) },
    ) { safePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(safePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TabsHeader(
                normalCount = normalCount,
                selectedTabs = selectedTabs,
                modeTabCount = tabs.count { it.browsingMode == mode },
                bookmarkedUrls = bookmarkedUrls,
                privateModeVisible = showPrivateMode,
                mode = mode,
                pagerState = pagerState,
                privateBrowsingAvailable = privateBrowsingAvailable,
                menuExpanded = menuExpanded,
                onMenuChange = { menuExpanded = it },
                onNewCurrentModeTab = {
                    if (mode == BrowsingMode.Private) onNewPrivateTab() else onNewTab()
                },
                onNewNormalTab = onNewTab,
                onNewPrivateTab = onNewPrivateTab,
                onCloseAll = {
                    menuExpanded = false
                    onCloseTabs(mode)
                },
                onClearSelection = { selectedTabIds = emptySet() },
                onSelectAll = {
                    selectedTabIds = tabs.asSequence()
                        .filter { it.browsingMode == mode }
                        .mapTo(mutableSetOf()) { it.id }
                },
                onCloseSelected = {
                    val ids = selectedTabIds
                    selectedTabIds = emptySet()
                    onCloseSelectedTabs(ids)
                },
                onSetSelectedPinned = { pinned ->
                    onSetTabsPinned(selectedTabIds, pinned)
                },
                onSetSelectedBookmarked = { bookmarked ->
                    onSetTabsBookmarked(selectedTabs, bookmarked)
                },
                onShareSelected = {
                    selectedTabs.singleOrNull()?.let(onShareTab)
                },
            )

            AppSearchField(
                query = query,
                onQueryChange = { query = it.limitCodePoints(MAX_QUERY_LENGTH) },
                placeholder = stringResource(R.string.search_tabs),
                modifier = Modifier
                    .widthIn(max = SEARCH_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = privateTabsExist,
                beyondViewportPageCount = 0,
                key = { it },
            ) { page ->
                val workspaceMode = if (page == PRIVATE_PAGE) BrowsingMode.Private else BrowsingMode.Normal
                val workspaceTabs = tabs.asSequence()
                    .filter { it.browsingMode == workspaceMode }
                    .filter { tab ->
                        query.isBlank() || tab.title.contains(query, ignoreCase = true) ||
                            tab.url.contains(query, ignoreCase = true)
                    }
                    .toList()
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (workspaceTabs.isEmpty()) {
                        EmptyTabsMessage(
                            if (query.isBlank()) stringResource(R.string.new_tab)
                            else stringResource(R.string.no_matching_tabs)
                        )
                    } else {
                        TabGrid(
                            tabs = workspaceTabs,
                            activeTabId = activeTabId,
                            bookmarkedUrls = bookmarkedUrls,
                            thumbnailFor = thumbnailFor,
                            runtimeFaviconFor = runtimeFaviconFor,
                            selectedTabIds = selectedTabIds,
                            selectionMode = selectionMode,
                            reorderEnabled = query.isBlank() && !selectionMode,
                            onTabClick = { id -> if (selectionMode) toggleSelection(id) else onTabClick(id) },
                            onSelect = ::toggleSelection,
                            onSetPinned = onSetTabPinned,
                            onReorder = onReorderTab,
                            onBookmark = onBookmarkTab,
                            onShare = onShareTab,
                            onClose = { tab ->
                                onCloseTab(tab.id)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = resources.getString(
                                            R.string.closed_tab,
                                            tab.title.ifBlank { UrlDisplay.hostOrUrl(tab.url) },
                                        ),
                                        actionLabel = undoLabel,
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onReopenTab(tab)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabsHeader(
    normalCount: Int,
    selectedTabs: List<TabItem>,
    modeTabCount: Int,
    bookmarkedUrls: Set<String>,
    privateModeVisible: Boolean,
    mode: BrowsingMode,
    pagerState: PagerState,
    privateBrowsingAvailable: Boolean,
    menuExpanded: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onNewCurrentModeTab: () -> Unit,
    onNewNormalTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onCloseAll: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onCloseSelected: () -> Unit,
    onSetSelectedPinned: (Boolean) -> Unit,
    onSetSelectedBookmarked: (Boolean) -> Unit,
    onShareSelected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val normalTabsDescription = stringResource(R.string.tabs_count_accessibility, normalCount)
    val modeControlWidth by animateDpAsState(
        targetValue = if (privateModeVisible) 112.dp else 60.dp,
        label = "tab-mode-width",
    )

    if (selectedTabs.isNotEmpty()) {
        val bookmarkableTabs = selectedTabs.filter { isSafeLoadableUrl(it.url) }
        val removeBookmarks = bookmarkableTabs.isNotEmpty() &&
            bookmarkableTabs.all { it.url in bookmarkedUrls }
        val unpinTabs = selectedTabs.all(TabItem::isPinned)
        AppSelectionTopAppBar(
            selectedCount = selectedTabs.size,
            onClearSelection = onClearSelection,
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.widthIn(max = HEADER_MAX_WIDTH).fillMaxWidth(),
            actions = {
                Box {
                    IconButton(onClick = { onMenuChange(true) }) {
                        Icon(MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    AppOverflowMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuChange(false) },
                    ) {
                        if (selectedTabs.size < modeTabCount) {
                            AppOverflowMenuItem(
                                text = stringResource(R.string.select_all_tabs),
                                leadingIcon = { Icon(SelectAll, contentDescription = null) },
                                onClick = {
                                    onMenuChange(false)
                                    onSelectAll()
                                },
                            )
                        }
                        AppOverflowMenuItem(
                            text = stringResource(
                                if (selectedTabs.size == 1) R.string.close_tab
                                else R.string.close_selected_tabs
                            ),
                            leadingIcon = { Icon(Close, contentDescription = null) },
                            onClick = {
                                onMenuChange(false)
                                onCloseSelected()
                            },
                        )
                        if (bookmarkableTabs.isNotEmpty()) {
                            AppOverflowMenuItem(
                                text = stringResource(
                                    when {
                                        removeBookmarks && selectedTabs.size == 1 ->
                                            R.string.remove_bookmark
                                        removeBookmarks -> R.string.remove_selected_tab_bookmarks
                                        selectedTabs.size == 1 -> R.string.bookmark_this_page
                                        else -> R.string.bookmark_selected_tabs
                                    }
                                ),
                                leadingIcon = {
                                    Icon(
                                        if (removeBookmarks) Bookmark else BookmarkBorder,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onMenuChange(false)
                                    onSetSelectedBookmarked(!removeBookmarks)
                                },
                            )
                        }
                        if (selectedTabs.size == 1 && isSafeLoadableUrl(selectedTabs.single().url)) {
                            AppOverflowMenuItem(
                                text = stringResource(R.string.share),
                                leadingIcon = { Icon(Share, contentDescription = null) },
                                onClick = {
                                    onMenuChange(false)
                                    onShareSelected()
                                },
                            )
                        }
                        AppOverflowMenuItem(
                            text = stringResource(
                                when {
                                    unpinTabs && selectedTabs.size == 1 -> R.string.unpin_tab
                                    unpinTabs -> R.string.unpin_selected_tabs
                                    selectedTabs.size == 1 -> R.string.pin_tab
                                    else -> R.string.pin_selected_tabs
                                }
                            ),
                            leadingIcon = { Icon(PushPin, contentDescription = null) },
                            onClick = {
                                onMenuChange(false)
                                onSetSelectedPinned(!unpinTabs)
                            },
                        )
                    }
                }
            },
        )
        return
    }

    Row(
        modifier = Modifier
            .widthIn(max = HEADER_MAX_WIDTH)
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = onNewCurrentModeTab,
            enabled = mode != BrowsingMode.Private || privateBrowsingAvailable,
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                Add,
                contentDescription = stringResource(
                    if (mode == BrowsingMode.Private) R.string.new_private_tab else R.string.new_tab
                ),
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PillTabBar(
                pagerState = pagerState,
                tabCount = if (privateModeVisible) 2 else 1,
                modifier = Modifier.width(modeControlWidth),
            ) {
                PillTab(
                    index = NORMAL_PAGE,
                    onClick = { scope.launch { pagerState.animateScrollToPage(NORMAL_PAGE) } },
                    text = {
                        AppTabCountIcon(
                            count = normalCount,
                            color = LocalContentColor.current,
                            size = 24.dp,
                            modifier = Modifier.semantics {
                                contentDescription = normalTabsDescription
                            },
                        )
                    },
                )
                if (privateModeVisible) {
                    PillTab(
                        index = PRIVATE_PAGE,
                        onClick = { scope.launch { pagerState.animateScrollToPage(PRIVATE_PAGE) } },
                        text = {
                            Icon(
                                Incognito,
                                contentDescription = stringResource(R.string.private_tabs),
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                }
            }
        }

        Box {
            IconButton(onClick = { onMenuChange(true) }) {
                Icon(MoreVert, contentDescription = stringResource(R.string.tab_switcher_more))
            }
            AppOverflowMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuChange(false) },
            ) {
                AppOverflowMenuItem(
                    text = stringResource(R.string.new_tab),
                    leadingIcon = { Icon(Add, contentDescription = null) },
                    onClick = {
                        onMenuChange(false)
                        onNewNormalTab()
                    },
                )
                AppOverflowMenuItem(
                    text = stringResource(R.string.new_private_tab),
                    leadingIcon = { Icon(Incognito, contentDescription = null) },
                    enabled = privateBrowsingAvailable,
                    onClick = {
                        onMenuChange(false)
                        onNewPrivateTab()
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                AppOverflowMenuItem(
                    text = stringResource(R.string.close_all_tabs),
                    leadingIcon = { Icon(Close, contentDescription = null) },
                    onClick = onCloseAll,
                )
            }
        }
    }
}

@Composable
private fun TabGrid(
    tabs: List<TabItem>,
    activeTabId: Long?,
    bookmarkedUrls: Set<String>,
    selectedTabIds: Set<Long>,
    selectionMode: Boolean,
    reorderEnabled: Boolean,
    thumbnailFor: (Long) -> Bitmap?,
    runtimeFaviconFor: (Long) -> Bitmap?,
    onTabClick: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onReorder: (Long, Int) -> Unit,
    onBookmark: (TabItem) -> Unit,
    onShare: (TabItem) -> Unit,
    onClose: (TabItem) -> Unit,
) {
    val adaptive = rememberAdaptiveLayoutInfo()
    val minimumCellWidth = if (adaptive.isCompact) 156.dp else adaptive.gridMinCellWidth
    val gridState = rememberLazyGridState()
    val reorder = remember {
        TabGridReorderState<TabItem>(
            idOf = { it.id },
            canCross = { dragged, candidate -> dragged.isPinned == candidate.isPinned },
        ).apply { sync(tabs) }
    }
    val density = LocalDensity.current
    val autoScrollEdgePx = with(density) { 72.dp.toPx() }
    val maximumAutoScrollPerSecondPx = with(density) { 720.dp.toPx() }

    fun visibleLayouts(): List<TabGridItemLayout> =
        gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val id = info.key as? Long ?: return@mapNotNull null
            TabGridItemLayout(
                id = id,
                center = Offset(
                    info.offset.x + info.size.width / 2f,
                    info.offset.y + info.size.height / 2f,
                ),
                offset = Offset(info.offset.x.toFloat(), info.offset.y.toFloat()),
            )
        }

    LaunchedEffect(tabs) { reorder.sync(tabs) }
    LaunchedEffect(reorderEnabled) {
        if (!reorderEnabled) reorder.cancel(tabs)
    }
    LaunchedEffect(reorder.draggedId) {
        var previousFrameNanos = 0L
        while (reorder.draggedId != null) {
            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) 0f
            else (frameNanos - previousFrameNanos) / 1_000_000_000f
            previousFrameNanos = frameNanos
            val layoutInfo = gridState.layoutInfo
            val pointerY = reorder.pointerCenter.y
            val intensity = when {
                !pointerY.isFinite() -> 0f
                pointerY < layoutInfo.viewportStartOffset + autoScrollEdgePx -> {
                    -((layoutInfo.viewportStartOffset + autoScrollEdgePx - pointerY) /
                        autoScrollEdgePx).coerceIn(0f, 1f)
                }
                pointerY > layoutInfo.viewportEndOffset - autoScrollEdgePx -> {
                    ((pointerY - (layoutInfo.viewportEndOffset - autoScrollEdgePx)) /
                        autoScrollEdgePx).coerceIn(0f, 1f)
                }
                else -> 0f
            }
            if (intensity != 0f && elapsedSeconds > 0f) {
                val consumed = gridState.scrollBy(
                    intensity * maximumAutoScrollPerSecondPx * elapsedSeconds.coerceAtMost(0.05f)
                )
                reorder.compensateForScroll(consumed)
                reorder.reevaluate(visibleLayouts())
            }
        }
    }
    val displayedTabs = reorder.items(tabs)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minimumCellWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize().widthIn(max = adaptive.contentMaxWidth),
        // Scaffold already applies safe-drawing insets; only card breathing room is added here.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = reorder.draggedId == null,
    ) {
        itemsIndexed(displayedTabs, key = { _, tab -> tab.id }) { tabIndex, tab ->
            val dragging = reorder.isDragging(tab.id)
            TabCard(
                tab = tab,
                active = tab.id == activeTabId,
                selected = tab.id in selectedTabIds,
                bookmarked = tab.url in bookmarkedUrls,
                selectionMode = selectionMode,
                reorderEnabled = reorderEnabled,
                dragging = dragging,
                thumbnail = thumbnailFor(tab.id),
                runtimeFavicon = runtimeFaviconFor(tab.id),
                onClick = { onTabClick(tab.id) },
                onSelect = { onSelect(tab.id) },
                onSetPinned = { onSetPinned(tab.id, it) },
                canMovePrevious = tabIndex > 0 &&
                    displayedTabs[tabIndex - 1].isPinned == tab.isPinned,
                canMoveNext = tabIndex < displayedTabs.lastIndex &&
                    displayedTabs[tabIndex + 1].isPinned == tab.isPinned,
                onMovePrevious = { onReorder(tab.id, tabIndex - 1) },
                onMoveNext = { onReorder(tab.id, tabIndex + 1) },
                onDragStart = {
                    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tab.id }
                    if (info != null) {
                        reorder.start(
                            id = tab.id,
                            center = Offset(
                                x = info.offset.x + info.size.width / 2f,
                                y = info.offset.y + info.size.height / 2f,
                            ),
                        )
                    }
                },
                onDrag = { delta -> reorder.dragBy(delta, visibleLayouts()) },
                onDragEnd = {
                    reorder.finish()?.let { commit -> onReorder(commit.tabId, commit.targetIndex) }
                },
                onDragCancel = { reorder.cancel(tabs) },
                onBookmark = { onBookmark(tab) },
                onShare = { onShare(tab) },
                onClose = { onClose(tab) },
                modifier = Modifier
                    .then(if (dragging) Modifier.zIndex(2f).graphicsLayer {
                        translationX = reorder.dragOffset.x
                        translationY = reorder.dragOffset.y
                        scaleX = 1.03f
                        scaleY = 1.03f
                        shadowElevation = 12.dp.toPx()
                    } else Modifier.animateItem()),
            )
        }
    }
}

@Composable
private fun TabCard(
    tab: TabItem,
    active: Boolean,
    selected: Boolean,
    bookmarked: Boolean,
    selectionMode: Boolean,
    reorderEnabled: Boolean,
    dragging: Boolean,
    thumbnail: Bitmap?,
    runtimeFavicon: Bitmap?,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    canMovePrevious: Boolean,
    canMoveNext: Boolean,
    onMovePrevious: () -> Unit,
    onMoveNext: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    val selectionColor = MaterialTheme.colorScheme.primary
    val clickLabel = stringResource(if (selectionMode) R.string.select else R.string.open_tab)
    val selectLabel = stringResource(R.string.select)
    val haptics = LocalHapticFeedback.current
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    var menuExpanded by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = dismissState,
        onDismiss = { onClose() },
        enableDismissFromStartToEnd = !selectionMode && !dragging,
        enableDismissFromEndToStart = !selectionMode && !dragging,
        modifier = modifier.fillMaxWidth().clip(shape),
        backgroundContent = { Spacer(Modifier.fillMaxSize()) },
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(TAB_CARD_ASPECT_RATIO)
                .graphicsLayer {
                    // SwipeToDismissBox owns translation. Fade from the absolute travelled
                    // distance so a settled or cancelled card is always fully opaque.
                    val swipeOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
                    val swipeProgress = if (size.width > 0) {
                        abs(swipeOffset) / size.width
                    } else {
                        0f
                    }
                    alpha = 1f - swipeProgress.coerceIn(0f, 1f) * 0.72f
                }
                .semantics { this.selected = selected }
                .combinedClickable(
                    onClickLabel = clickLabel,
                    onClick = onClick,
                    onLongClickLabel = if (selectionMode) selectLabel else null,
                    onLongClick = if (selectionMode) onSelect else null,
                )
                .then(
                    if (reorderEnabled) Modifier.pointerInput(tab.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnDragStart()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                currentOnDrag(amount)
                            },
                            onDragEnd = currentOnDragEnd,
                            onDragCancel = currentOnDragCancel,
                        )
                    } else Modifier
                )
                .then(
                    if (selected) Modifier.border(width = 3.dp, color = selectionColor, shape = shape)
                    else Modifier
                ),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (active) selectionColor else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteFavicon(
                        pageUrl = tab.url,
                        runtimeBitmap = runtimeFavicon,
                        allowPersistentLookup = !tab.isPrivate,
                        size = 20.dp,
                    )
                    Text(
                        text = tab.title.ifBlank { UrlDisplay.hostOrUrl(tab.url) },
                        modifier = Modifier.weight(1f).padding(start = 9.dp, end = 2.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab.isPinned) {
                        Icon(
                            PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (selectionMode) {
                        AppSelectionIndicator(
                            selected = selected,
                            onClick = onSelect,
                            colors = if (active) {
                                // The active header is primary; keep the checkbox legible on it.
                                CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.onPrimary,
                                    uncheckedColor = MaterialTheme.colorScheme.onPrimary,
                                    checkmarkColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                CheckboxDefaults.colors()
                            },
                        )
                    } else {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (active) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AppOverflowMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                AppOverflowMenuItem(
                                    text = stringResource(R.string.select),
                                    leadingIcon = { Icon(CheckCircle, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onSelect()
                                    },
                                )
                                AppOverflowMenuItem(
                                    text = stringResource(
                                        if (tab.isPinned) R.string.unpin_tab else R.string.pin_tab
                                    ),
                                    leadingIcon = { Icon(PushPin, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onSetPinned(!tab.isPinned)
                                    },
                                )
                                if (canMovePrevious) {
                                    AppOverflowMenuItem(
                                        text = stringResource(R.string.move_up),
                                        onClick = {
                                            menuExpanded = false
                                            onMovePrevious()
                                        },
                                    )
                                }
                                if (canMoveNext) {
                                    AppOverflowMenuItem(
                                        text = stringResource(R.string.move_down),
                                        onClick = {
                                            menuExpanded = false
                                            onMoveNext()
                                        },
                                    )
                                }
                                AppOverflowMenuItem(
                                    text = stringResource(
                                        if (bookmarked) R.string.remove_bookmark
                                        else R.string.bookmark_this_page
                                    ),
                                    leadingIcon = {
                                        Icon(
                                            if (bookmarked) Bookmark else BookmarkBorder,
                                            contentDescription = null,
                                        )
                                    },
                                    enabled = isSafeLoadableUrl(tab.url),
                                    onClick = {
                                        menuExpanded = false
                                        onBookmark()
                                    },
                                )
                                if (isSafeLoadableUrl(tab.url)) {
                                    AppOverflowMenuItem(
                                        text = stringResource(R.string.share),
                                        leadingIcon = { Icon(Share, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            onShare()
                                        },
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                                AppOverflowMenuItem(
                                    text = stringResource(R.string.close_tab),
                                    leadingIcon = { Icon(Close, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onClose()
                                    },
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null && !thumbnail.isRecycled) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        TabPreviewPlaceholder()
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPreviewPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        PreviewPlaceholderLine(widthFraction = 0.72f)
        Spacer(Modifier.height(12.dp))
        PreviewPlaceholderLine(widthFraction = 0.88f)
        Spacer(Modifier.height(12.dp))
        PreviewPlaceholderLine(widthFraction = 0.54f)
    }
}

@Composable
private fun PreviewPlaceholderLine(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun EmptyTabsMessage(message: String) {
    AppEmptyState(
        icon = Search,
        title = message,
        modifier = Modifier.fillMaxSize(),
    )
}

internal fun shouldShowPrivateMode(privateCount: Int, currentPage: Int): Boolean =
    privateCount > 0 || currentPage == PRIVATE_PAGE

internal fun workspacePageFor(activeIsPrivate: Boolean, privateTabsExist: Boolean): Int =
    if (activeIsPrivate && privateTabsExist) PRIVATE_PAGE else NORMAL_PAGE

private const val MAX_QUERY_LENGTH = 256
internal const val NORMAL_PAGE = 0
internal const val PRIVATE_PAGE = 1
private const val WORKSPACE_PAGE_COUNT = 2
private const val TAB_CARD_ASPECT_RATIO = 0.74f
private val HEADER_MAX_WIDTH: Dp = 920.dp
private val SEARCH_MAX_WIDTH: Dp = 720.dp
