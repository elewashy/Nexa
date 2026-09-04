package com.elewashy.nexa.feature.downloads.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout
import com.elewashy.nexa.feature.downloads.presentation.components.descriptionRes
import com.elewashy.nexa.feature.downloads.presentation.components.labelRes
import com.elewashy.nexa.ui.components.settings.PhoneDesignSelectorScreen
import com.elewashy.nexa.ui.components.settings.PhonePreviewFrame
import com.elewashy.nexa.ui.components.settings.SettingsLoadingContent
import com.elewashy.nexa.ui.icons.AudioFile
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.InsertDriveFile
import com.elewashy.nexa.ui.icons.PlayArrowFilled
import com.elewashy.nexa.ui.icons.VideoFile

@Composable
fun DownloadLayoutSettingsRoute(
    onBackClick: () -> Unit,
    viewModel: DownloadLayoutViewModel = hiltViewModel(),
) {
    val presentation by viewModel.presentation.collectAsStateWithLifecycle()
    val loadedPresentation = presentation
    if (loadedPresentation == null) {
        SettingsLoadingContent()
        return
    }
    DownloadLayoutSettingsScreen(
        selectedLayout = loadedPresentation.layout,
        onLayoutSelected = viewModel::setLayout,
        onBackClick = onBackClick,
    )
}

@Composable
fun DownloadLayoutSettingsScreen(
    selectedLayout: DownloadManagerLayout,
    onLayoutSelected: (DownloadManagerLayout) -> Unit,
    onBackClick: () -> Unit,
    bottomBar: @Composable (() -> Unit)? = null,
) {
    PhoneDesignSelectorScreen(
        title = stringResource(R.string.download_layout_title),
        description = stringResource(R.string.download_layout_description),
        options = DownloadManagerLayout.entries,
        selectedOption = selectedLayout,
        optionTitle = { layout -> stringResource(layout.labelRes) },
        optionDescription = { layout -> stringResource(layout.descriptionRes) },
        applyLabel = stringResource(R.string.download_layout_apply),
        appliedLabel = stringResource(R.string.download_layout_currently_applied),
        onOptionSelected = onLayoutSelected,
        onBackClick = onBackClick,
        preview = { layout, modifier -> PhoneLayoutPreview(layout, modifier) },
        bottomBar = bottomBar,
    )
}

@Composable
private fun PhoneLayoutPreview(layout: DownloadManagerLayout, modifier: Modifier = Modifier) {
    PhonePreviewFrame(modifier = modifier) {
        val miniature = maxWidth < 130.dp
        Column(Modifier.padding(if (miniature) 6.dp else 10.dp)) {
            Box(
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .width(if (miniature) 34.dp else 54.dp)
                    .height(if (miniature) 3.dp else 5.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)
            )
            if (!miniature) {
                Text(
                    stringResource(R.string.downloads),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
            when {
                miniature -> MiniaturePreview(layout)
                layout == DownloadManagerLayout.MediaGallery -> MediaGalleryPreview()
                else -> TabbedListPreview()
            }
        }
    }
}

@Composable
private fun MiniaturePreview(layout: DownloadManagerLayout) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (layout == DownloadManagerLayout.TabbedList) {
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(5.dp))) {
                Box(Modifier.weight(1f).fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer))
                Box(Modifier.weight(1f).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(Modifier.width(18.dp).height(9.dp).clip(CircleShape).background(
                        if (it == 0) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer
                    ))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
        repeat(if (layout == DownloadManagerLayout.TabbedList) 4 else 2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun MediaGalleryPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PreviewChip(stringResource(R.string.download_filter_all), true)
            PreviewChip(stringResource(R.string.download_filter_videos), false)
        }
        Text(stringResource(R.string.download_date_today), style = MaterialTheme.typography.labelSmall)
        PreviewVideoCard(height = 128.dp)
        PreviewDownloadRow(VideoFile, "Mountain video.mp4")
        PreviewDownloadRow(InsertDriveFile, "Document.pdf")
    }
}

@Composable
private fun TabbedListPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Box(
                modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.downloads_tab_active), style = MaterialTheme.typography.labelSmall) }
            Box(modifier = Modifier.weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.downloads_tab_completed), style = MaterialTheme.typography.labelSmall)
            }
        }
        PreviewVideoCard(height = 76.dp)
        PreviewDownloadRow(VideoFile, "Video.mp4")
        PreviewDownloadRow(AudioFile, "Audio.m4a")
        PreviewDownloadRow(InsertDriveFile, "Document.pdf")
    }
}

@Composable
private fun PreviewVideoCard(height: Dp) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(PlayArrowFilled, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PreviewChip(label: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(Check, contentDescription = null, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun PreviewDownloadRow(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
        }
        Text(
            title,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
