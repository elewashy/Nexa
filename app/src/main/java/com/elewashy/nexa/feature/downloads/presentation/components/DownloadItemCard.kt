package com.elewashy.nexa.feature.downloads.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.fileTypeIcon
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.fileTypeIconTint
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.formatActiveStatusPrimary
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.formatActiveStatusSecondary
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.formatCompletedDownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.isActiveStatus
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadFormatters.statusTint
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.MoreVert
import com.elewashy.nexa.ui.icons.PauseFilled
import com.elewashy.nexa.ui.icons.PlayArrowFilled
import com.elewashy.nexa.ui.icons.RefreshFilled

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadItemCard(
    item: DownloadItem,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPauseClick: (() -> Unit)? = null,
    onResumeClick: (() -> Unit)? = null,
    onCancelClick: (() -> Unit)? = null,
    onRetryClick: (() -> Unit)? = null,
    onMoreOptionsClick: (() -> Unit)? = null
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val containerColor = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent

    val isActive = isActiveStatus(item.status)
    val primaryStatus = if (isActive) formatActiveStatusPrimary(context, item) else ""
    val secondaryStatus = if (isActive) formatActiveStatusSecondary(context, item) else ""
    val completedStatus = if (!isActive) formatCompletedDownloadStatus(context, item) else ""

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = containerColor),
        leadingContent = {
            LeadingIcon(
                item = item,
                isSelected = isSelected,
                accentColor = accentColor,
                onPauseClick = onPauseClick,
                onResumeClick = onResumeClick
            )
        },
        headlineContent = {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (isActive) {
                    if (primaryStatus.isNotEmpty()) {
                        Text(
                            text = primaryStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (secondaryStatus.isNotEmpty()) {
                        if (primaryStatus.isNotEmpty()) Spacer(Modifier.height(2.dp))
                        Text(
                            text = secondaryStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (completedStatus.isNotEmpty()) {
                    Text(
                        text = completedStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusTint(item.status, MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        trailingContent = if (!isMultiSelectMode) {
            {
                TrailingAction(
                    item = item,
                    onCancelClick = onCancelClick,
                    onRetryClick = onRetryClick,
                    onMoreOptionsClick = onMoreOptionsClick
                )
            }
        } else null
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LeadingIcon(
    item: DownloadItem,
    isSelected: Boolean,
    accentColor: Color,
    onPauseClick: (() -> Unit)?,
    onResumeClick: (() -> Unit)?
) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isSelected -> {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED -> {
                val trackColor = MaterialTheme.colorScheme.surfaceVariant
                val stroke = rememberProgressStroke()

                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.size(40.dp),
                        color = if (item.status == DownloadStatus.DOWNLOADING) accentColor else accentColor.copy(alpha = 0.5f),
                        trackColor = trackColor,
                        stroke = stroke,
                        trackStroke = stroke,
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                onClick = {
                                    when (item.status) {
                                        DownloadStatus.DOWNLOADING -> onPauseClick?.invoke()
                                        DownloadStatus.PAUSED -> onResumeClick?.invoke()
                                        else -> {}
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (item.status == DownloadStatus.PAUSED) PlayArrowFilled else PauseFilled,
                            contentDescription = if (item.status == DownloadStatus.PAUSED) stringResource(R.string.resume) else stringResource(R.string.pause),
                            tint = accentColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            item.status == DownloadStatus.PENDING -> {
                val stroke = rememberProgressStroke()
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    stroke = stroke,
                    trackStroke = stroke,
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fileTypeIcon(item),
                        contentDescription = null,
                        tint = fileTypeIconTint(item, accentColor),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrailingAction(
    item: DownloadItem,
    onCancelClick: (() -> Unit)?,
    onRetryClick: (() -> Unit)?,
    onMoreOptionsClick: (() -> Unit)?
) {
    when (item.status) {
        DownloadStatus.DOWNLOADING,
        DownloadStatus.PENDING,
        DownloadStatus.PAUSED -> {
            IconButton(onClick = { onCancelClick?.invoke() }) {
                Icon(
                    imageVector = Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DownloadStatus.FAILED -> {
            IconButton(onClick = { onRetryClick?.invoke() }) {
                Icon(
                    imageVector = RefreshFilled,
                    contentDescription = stringResource(R.string.retry),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        DownloadStatus.COMPLETED,
        DownloadStatus.CANCELLED -> {
            IconButton(onClick = { onMoreOptionsClick?.invoke() }) {
                Icon(
                    imageVector = MoreVert,
                    contentDescription = stringResource(R.string.more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun rememberProgressStroke(): Stroke {
    val density = LocalDensity.current
    return remember(density) { Stroke(width = with(density) { 2.5.dp.toPx() }) }
}
