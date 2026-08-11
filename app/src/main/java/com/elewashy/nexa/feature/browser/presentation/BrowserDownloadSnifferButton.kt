package com.elewashy.nexa.feature.browser.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Download

/**
 * Floating affordance shown when the current page belongs to a supported
 * video platform.
 *
 * Interaction model:
 * - Tap opens the download sheet for the current page.
 * - Long-press morphs the icon into a close button; tapping the close button
 *   hides the sniffer until the next page load. Long-pressing again restores
 *   the download state without hiding it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserDownloadSnifferButton(
    dismissMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (dismissMode) R.string.close else R.string.download)

    Surface(
        modifier = modifier
            .semantics { text = AnnotatedString(label) }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = SNIFFER_ELEVATION,
    ) {
        AnimatedContent(
            targetState = dismissMode,
            transitionSpec = {
                (scaleIn(initialScale = 0.6f) + fadeIn())
                    .togetherWith(scaleOut(targetScale = 0.6f) + fadeOut())
            },
            label = "snifferIcon",
            modifier = Modifier
                .size(SNIFFER_SIZE)
                .padding(SNIFFER_ICON_PADDING),
        ) { isDismiss ->
            Icon(
                imageVector = if (isDismiss) Close else Download,
                contentDescription = null,
            )
        }
    }
}

private val SNIFFER_SIZE = 56.dp
private val SNIFFER_ICON_PADDING = 16.dp
private val SNIFFER_ELEVATION = 4.dp
