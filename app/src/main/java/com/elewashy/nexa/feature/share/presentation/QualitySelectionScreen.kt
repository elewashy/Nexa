package com.elewashy.nexa.feature.share.presentation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import com.elewashy.nexa.ui.adaptive.adaptiveGridColumns
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.PagerSegmentedControl
import com.elewashy.nexa.ui.components.common.PillTab
import com.elewashy.nexa.ui.icons.AudioFile
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.VideoFile
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.launch
import kotlin.math.ceil

private enum class QualityTab(val type: VideoQuality.MediaType) {
    Video(VideoQuality.MediaType.VIDEO),
    Audio(VideoQuality.MediaType.AUDIO),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QualitySelectionScreen(
    platform: String,
    audioQualities: List<VideoQuality>,
    videoQualities: List<VideoQuality>,
    isLoading: Boolean,
    sizeLoading: Boolean = false,
    onDownload: (VideoQuality) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val allQualities = remember(videoQualities, audioQualities) { videoQualities + audioQualities }
    val availableTabs = remember(videoQualities, audioQualities) {
        buildList {
            if (videoQualities.isNotEmpty()) add(QualityTab.Video)
            if (audioQualities.isNotEmpty()) add(QualityTab.Audio)
        }
    }
    var selectedQuality by remember { mutableStateOf<VideoQuality?>(null) }
    val pagerState = rememberPagerState(pageCount = { availableTabs.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    val maxSheetContentHeight = remember(adaptiveInfo.heightDp, adaptiveInfo.isLandscape) {
        val heightFraction = if (adaptiveInfo.isLandscape) 0.92f else 0.86f
        (adaptiveInfo.heightDp.dp * heightFraction).coerceAtLeast(360.dp)
    }

    LaunchedEffect(allQualities) {
        selectedQuality = selectedQuality?.let { selected ->
            allQualities.find { it.stableKey() == selected.stableKey() }
        } ?: availableTabs.firstOrNull()?.qualityFrom(
            videoQualities = videoQualities,
            audioQualities = audioQualities,
        )
        val selectedType = selectedQuality?.type
        val selectedPage = availableTabs.indexOfFirst { it.type == selectedType }
        if (selectedPage >= 0) {
            pagerState.scrollToPage(selectedPage)
        }
    }

    LaunchedEffect(pagerState.currentPage, availableTabs, videoQualities, audioQualities) {
        val tab = availableTabs.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (selectedQuality?.type != tab.type) {
            selectedQuality = tab.qualityFrom(
                videoQualities = videoQualities,
                audioQualities = audioQualities,
            )
        }
    }

    Column(
        modifier = modifier
            .widthIn(max = adaptiveInfo.sheetMaxWidth)
            .heightIn(max = maxSheetContentHeight)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        QualitySheetHeader(platform = platform)

        Spacer(Modifier.height(20.dp))

        if (isLoading) {
            QualityOptionsLoadingState()
        } else {
            QualityOptionsPager(
                audioQualities = audioQualities,
                videoQualities = videoQualities,
                selectedQuality = selectedQuality,
                tabs = availableTabs,
                pagerState = pagerState,
                tabMaxWidth = adaptiveInfo.listMaxWidth,
                sizeLoading = sizeLoading,
                onTypeSelected = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                onQualitySelected = { quality ->
                    selectedQuality = quality
                },
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.cancel))
            }

            Button(
                onClick = { selectedQuality?.let(onDownload) },
                enabled = !isLoading && selectedQuality != null,
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.download))
            }
        }
    }
}

@Composable
private fun QualitySheetHeader(platform: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.select_quality),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = platform.takeIf { it.isNotBlank() } ?: stringResource(R.string.select_quality_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QualityOptionsPager(
    audioQualities: List<VideoQuality>,
    videoQualities: List<VideoQuality>,
    selectedQuality: VideoQuality?,
    tabs: List<QualityTab>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    tabMaxWidth: Dp,
    sizeLoading: Boolean,
    onTypeSelected: (Int) -> Unit,
    onQualitySelected: (VideoQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val currentTab = tabs.getOrNull(pagerState.currentPage)
        val currentQualities = when (currentTab) {
            QualityTab.Video -> videoQualities
            QualityTab.Audio -> audioQualities
            null -> emptyList()
        }
        val tabBarHeight = if (tabs.size > 1) 64.dp else 0.dp
        val maxPagerHeight = (maxHeight - tabBarHeight).coerceAtLeast(160.dp)
        val pagerHeight by animateDpAsState(
            targetValue = qualityPagerHeight(
                itemCount = currentQualities.size,
                availableWidth = maxWidth,
                maxHeight = maxPagerHeight,
            ),
            label = "qualityPagerHeight",
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            if (tabs.size > 1) {
                PagerSegmentedControl(
                    pagerState = pagerState,
                    maxWidth = tabMaxWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        PillTab(
                            index = index,
                            onClick = { onTypeSelected(index) },
                            text = { Text(stringResource(tab.titleRes)) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pagerHeight),
            ) { page ->
                when (tabs.getOrNull(page)) {
                    QualityTab.Video -> QualityTabContent(
                        qualities = videoQualities,
                        selectedQuality = selectedQuality,
                        sizeLoading = sizeLoading,
                        onQualitySelected = onQualitySelected,
                    )
                    QualityTab.Audio -> QualityTabContent(
                        qualities = audioQualities,
                        selectedQuality = selectedQuality,
                        sizeLoading = sizeLoading,
                        onQualitySelected = onQualitySelected,
                    )
                    null -> Unit
                }
            }
        }
    }
}

private val QualityTab.titleRes: Int
    get() = when (this) {
        QualityTab.Video -> R.string.video
        QualityTab.Audio -> R.string.audio
    }

private val QualityTab.icon: ImageVector
    get() = when (this) {
        QualityTab.Video -> VideoFile
        QualityTab.Audio -> AudioFile
    }

private fun QualityTab.qualityFrom(
    videoQualities: List<VideoQuality>,
    audioQualities: List<VideoQuality>,
): VideoQuality? = when (this) {
    QualityTab.Video -> videoQualities.firstOrNull()
    QualityTab.Audio -> audioQualities.firstOrNull()
}

private fun VideoQuality.stableKey(): String = "${type.name}:$url:$quality"

private fun qualityPagerHeight(itemCount: Int, availableWidth: Dp, maxHeight: Dp): Dp {
    val columns = adaptiveGridColumns(availableWidth, minCellWidth = 128.dp, minColumns = 1, maxColumns = 4)
    val rows = ceil(itemCount.coerceAtLeast(1) / columns.toFloat()).toInt()
    val desiredHeight = (rows * 64).dp + 4.dp
    return desiredHeight.coerceIn(160.dp, maxHeight)
}

@Composable
private fun QualityTabContent(
    qualities: List<VideoQuality>,
    selectedQuality: VideoQuality?,
    sizeLoading: Boolean,
    onQualitySelected: (VideoQuality) -> Unit,
) {
    if (qualities.isEmpty()) {
        EmptyQualityOptions()
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(qualities, key = { it.stableKey() }) { quality ->
            QualityOptionChip(
                quality = quality,
                selected = quality.stableKey() == selectedQuality?.stableKey(),
                sizeLoading = sizeLoading && quality.size == null,
                onClick = { onQualitySelected(quality) },
            )
        }
    }
}

@Composable
private fun EmptyQualityOptions() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.quality_options_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QualityOptionChip(
    quality: VideoQuality,
    selected: Boolean,
    sizeLoading: Boolean,
    onClick: () -> Unit,
) {
    val labels = quality.getDisplayLabels()
    ElevatedFilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        label = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = quality.qualityTitle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                when {
                    labels.metadata != null -> Text(
                        text = labels.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    sizeLoading -> ShimmerTextPlaceholder(widthFraction = 0.72f)
                    quality.hasWatermark -> Text(
                        text = stringResource(R.string.watermark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        leadingIcon = {
            Icon(
                imageVector = if (quality.type == VideoQuality.MediaType.AUDIO) AudioFile else VideoFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        horizontalArrangement = FilterChipDefaults.horizontalArrangement(
            hasLeadingIcon = true,
            hasTrailingIcon = selected,
        ),
    )
}

@Composable
private fun VideoQuality.qualityTitle(): String {
    val labels = getDisplayLabels()
    return if (labels.quality.equals("Watermark", ignoreCase = true)) {
        stringResource(R.string.watermark)
    } else {
        labels.quality
    }
}

@Composable
private fun QualityOptionsLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shimmer(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.preparing_quality_options),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        repeat(3) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(2) { column ->
                    ShimmerQualityChip(
                        widthFraction = if ((row + column) % 2 == 0) 0.48f else 0.64f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShimmerQualityChip(widthFraction: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(58.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShimmerTextPlaceholder(widthFraction = widthFraction)
        ShimmerTextPlaceholder(widthFraction = widthFraction * 0.72f)
    }
}

@Composable
private fun ShimmerTextPlaceholder(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
