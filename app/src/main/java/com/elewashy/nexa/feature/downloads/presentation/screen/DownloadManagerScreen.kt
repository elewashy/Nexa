package com.elewashy.nexa.feature.downloads.presentation.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadFilterCategory
import com.elewashy.nexa.feature.downloads.domain.model.DownloadFilterPolicy
import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadItemActions
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadItemCard
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadManagerHeader
import com.elewashy.nexa.feature.downloads.presentation.components.icon
import com.elewashy.nexa.feature.downloads.presentation.components.labelRes
import com.elewashy.nexa.feature.downloads.presentation.settings.DownloadManagerPresentation
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppEmptyState
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.PlayArrowFilled
import com.elewashy.nexa.ui.icons.Search
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    presentation: DownloadManagerPresentation,
    snackbarHostState: SnackbarHostState,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    actions: DownloadItemActions,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
) {
    when (presentation.layout) {
        DownloadManagerLayout.MediaGallery -> MediaGalleryDownloadsScreen(
            downloads = downloads,
            snackbarHostState = snackbarHostState,
            selectedItems = selectedItems,
            isMultiSelectMode = isMultiSelectMode,
            actions = actions,
            enabledFilters = presentation.enabledFilters,
            visualVideoPresentation = presentation.visualVideoPresentation,
            showFilterCounts = presentation.showFilterCounts,
            onBackClick = onBackClick,
            onSettingsClick = onSettingsClick,
            onDeleteSelected = onDeleteSelected,
            onClearSelection = onClearSelection,
        )
        DownloadManagerLayout.TabbedList -> TabbedListDownloadsScreen(
            downloads = downloads,
            snackbarHostState = snackbarHostState,
            selectedItems = selectedItems,
            isMultiSelectMode = isMultiSelectMode,
            actions = actions,
            visualVideoPresentation = presentation.visualVideoPresentation,
            onBackClick = onBackClick,
            onSettingsClick = onSettingsClick,
            onDeleteSelected = onDeleteSelected,
            onClearSelection = onClearSelection,
        )
    }
}

/**
 * Completed videos get the large preview treatment only when the user opted
 * in; every other item, including in-flight videos, renders as a list row.
 */
internal fun isFeaturedVideo(item: DownloadItem, visualVideoPresentation: Boolean): Boolean =
    visualVideoPresentation &&
        item.status == DownloadStatus.COMPLETED &&
        DownloadFormatters.isVideo(item)

private data class DownloadFilter(
    val category: DownloadFilterCategory?,
    val titleRes: Int,
    val icon: ImageVector,
    val count: Int,
)

private fun DownloadFilterCategory.toFilterOption(count: Int): DownloadFilter =
    DownloadFilter(this, labelRes, icon, count)

private data class DownloadSection(
    val date: LocalDate,
    val items: List<DownloadItem>,
    val newestCreatedAt: Long,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaGalleryDownloadsScreen(
    downloads: List<DownloadItem>,
    snackbarHostState: SnackbarHostState,
    selectedItems: Set<Long>,
    isMultiSelectMode: Boolean,
    actions: DownloadItemActions,
    enabledFilters: Set<DownloadFilterCategory>,
    visualVideoPresentation: Boolean,
    showFilterCounts: Boolean,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val availableFilters = remember(downloads, enabledFilters) {
        val visibleCounts = DownloadFilterPolicy.visibleCounts(downloads, enabledFilters)
        buildList {
            add(DownloadFilter(null, R.string.download_filter_all, Check, downloads.size))
            DownloadFilterCategory.entries.forEach { category ->
                visibleCounts[category]?.let { count -> add(category.toFilterOption(count)) }
            }
        }
    }
    val availableCategoryIds = remember(availableFilters) {
        availableFilters.mapNotNullTo(hashSetOf()) { it.category?.storedId }
    }
    var selectedCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = availableFilters.firstOrNull { it.category?.storedId == selectedCategoryId }
        ?: availableFilters.first()
    LaunchedEffect(availableCategoryIds, selectedCategoryId) {
        if (selectedCategoryId != null && selectedCategoryId !in availableCategoryIds) {
            selectedCategoryId = null
        }
    }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(downloads.isEmpty()) {
        if (downloads.isEmpty()) {
            searchVisible = false
            query = ""
            selectedCategoryId = null
        }
    }
    val adaptive = rememberAdaptiveLayoutInfo()
    val filtered = remember(downloads, enabledFilters, filter, query) {
        val normalizedQuery = query.trim()
        downloads.filter { item ->
            val categoryMatches = DownloadFilterPolicy.matches(
                item = item,
                selected = filter.category,
                enabled = enabledFilters,
            )
            categoryMatches && (normalizedQuery.isEmpty() ||
                item.fileName.contains(normalizedQuery, ignoreCase = true) ||
                item.url.contains(normalizedQuery, ignoreCase = true))
        }
    }
    val sections = remember(filtered) {
        val zone = ZoneId.systemDefault()
        filtered.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .entries.sortedByDescending { it.key }
            .map { DownloadSection(it.key, it.value, it.value.maxOf(DownloadItem::createdAt)) }
    }

    Scaffold(
        topBar = {
            Column {
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
                if (downloads.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = adaptive.horizontalPadding, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableFilters.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { selectedCategoryId = option.category?.storedId },
                                label = {
                                    val title = stringResource(option.titleRes)
                                    Text(
                                        if (showFilterCounts) {
                                            stringResource(R.string.download_filter_with_count, title, option.count)
                                        } else {
                                            title
                                        }
                                    )
                                },
                                leadingIcon = { Icon(option.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (filtered.isEmpty()) {
            MediaGalleryEmptyState(
                searching = query.isNotBlank() || filter.category != null,
                modifier = Modifier.padding(padding),
            )
        } else {
            // Scaffold's innerPadding already carries the navigation-bar inset; padding it
            // again here would double the space below the list.
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = adaptive.listMaxWidth).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    sections.forEach { section ->
                        item(key = "date-${section.date}", contentType = "date") {
                            Box(modifier = Modifier.animateItem()) {
                                DownloadDateHeader(section.date, section.newestCreatedAt)
                            }
                        }
                        items(
                            items = section.items,
                            key = { it.id },
                            contentType = {
                                if (isFeaturedVideo(it, visualVideoPresentation)) "featured-video" else "download"
                            },
                        ) { item ->
                            Box(modifier = Modifier.animateItem()) {
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
                                        showSourceHost = true,
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
internal fun FeaturedVideoDownload(
    item: DownloadItem,
    selected: Boolean,
    isMultiSelectMode: Boolean,
    actions: DownloadItemActions,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { actions.onClick(item) },
                        onLongClick = { actions.onLongClick(item) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val previewHeight = (maxWidth * 0.32f).coerceIn(104.dp, 160.dp)
                Box(
                    modifier = Modifier.fillMaxWidth().height(previewHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PlayArrowFilled,
                        contentDescription = stringResource(R.string.open_file),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            DownloadItemCard(
                item = item,
                isSelected = selected,
                isMultiSelectMode = isMultiSelectMode,
                actions = actions,
                showSourceHost = true,
            )
        }
    }
}

@Composable
private fun DownloadDateHeader(date: LocalDate, newestCreatedAt: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val isRecent = date == today &&
        System.currentTimeMillis() - newestCreatedAt in 0L..RECENT_DOWNLOAD_WINDOW_MS
    val label = when {
        isRecent -> stringResource(R.string.download_date_just_now)
        date == today -> stringResource(R.string.download_date_today)
        date == today.minusDays(1) -> stringResource(R.string.download_date_yesterday)
        else -> remember(date, locale) {
            LocalizedFormatters.mediumDate(date, locale)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

private const val RECENT_DOWNLOAD_WINDOW_MS = 60L * 60L * 1_000L

@Composable
private fun MediaGalleryEmptyState(searching: Boolean, modifier: Modifier = Modifier) {
    AppEmptyState(
        icon = if (searching) Search else Download,
        title = stringResource(
            if (searching) R.string.downloads_no_matches else R.string.downloads_visual_empty_title
        ),
        description = stringResource(
            if (searching) R.string.downloads_no_matches_desc else R.string.downloads_visual_empty_desc
        ),
        modifier = modifier.fillMaxSize(),
    )
}
