package com.elewashy.nexa.feature.browser.presentation.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.icons.FileDownload

@Composable
fun BrowserSnackbarHost(
    hostState: SnackbarHostState,
    downloadMessage: String,
    bottomOffset: Dp,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .padding(
                horizontal = BrowserDownloadSnackbarDefaults.EdgeMargin,
                vertical = bottomOffset,
            )
            .navigationBarsPadding(),
    ) { snackbarData ->
        if (snackbarData.visuals.message == downloadMessage) {
            BrowserDownloadSnackbar(snackbarData)
        } else {
            Snackbar(
                snackbarData,
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

object BrowserDownloadSnackbarDefaults {
    val EdgeMargin = 8.dp
    val IconSize = 24.dp
    val IconTextSpacing = 16.dp
    val BottomOffsetWithNavBar = 60.dp // 52dp browser nav bar + 8dp Material snackbar margin.
}

@Composable
private fun BrowserDownloadSnackbar(snackbarData: SnackbarData) {
    Snackbar(
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        action = {
            snackbarData.visuals.actionLabel?.let { label ->
                TextButton(onClick = snackbarData::performAction) {
                    Text(label)
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedDownloadIcon()
            Spacer(Modifier.width(BrowserDownloadSnackbarDefaults.IconTextSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snackbarData.visuals.message,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.browser_download_snackbar_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AnimatedDownloadIcon() {
    val transition = rememberInfiniteTransition(label = "downloadIconFill")
    val fillProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "downloadIconFillProgress",
    )
    val iconSize = BrowserDownloadSnackbarDefaults.IconSize

    Box(
        modifier = Modifier.size(iconSize),
        contentAlignment = Alignment.TopCenter,
    ) {
        Icon(
            imageVector = FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(iconSize),
        )
        Icon(
            imageVector = FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(iconSize)
                .drawWithContent {
                    clipRect(bottom = size.height * fillProgress) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
    }
}
