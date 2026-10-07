package com.elewashy.nexa.feature.browser.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Download
import kotlinx.coroutines.delay

/**
 * Floating download action shown while the current page offers downloadable media on a supported
 * platform (see `ResolveDownloadableMediaUseCase`).
 *
 * Follows Material 3's extended FAB pattern: it appears with its "Download" label so its purpose is
 * clear, then collapses to the icon so it covers less of the page. Tapping opens the download
 * sheet. Hiding it for the current page is offered without a hidden gesture mode: long-press opens
 * a menu with "Hide for this page", which accessibility services also expose as a custom action.
 *
 * @param pageKey identity of the current page load; a new page shows the label again.
 */
@Composable
fun BrowserDownloadButton(
    pageKey: String,
    onClick: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(pageKey) { mutableStateOf(true) }
    var menuOpen by rememberSaveable(pageKey) { mutableStateOf(false) }
    LaunchedEffect(pageKey) {
        delay(LABEL_VISIBLE_MS)
        expanded = false
    }

    val label = stringResource(R.string.download)
    val description = stringResource(R.string.download_button_content_description)
    val hideLabel = stringResource(R.string.download_button_hide)

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.defaultMinSize(minWidth = ButtonHeight, minHeight = ButtonHeight),
            shape = FloatingActionButtonDefaults.extendedFabShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = ButtonElevation,
        ) {
            Row(
                modifier = Modifier
                    .combinedClickable(
                        onClickLabel = label,
                        role = Role.Button,
                        onLongClickLabel = hideLabel,
                        onLongClick = { menuOpen = true },
                        onClick = onClick,
                    )
                    // One announcement for the whole button, whether or not the label shows.
                    .semantics {
                        contentDescription = description
                        customActions = listOf(
                            CustomAccessibilityAction(hideLabel) {
                                onHide()
                                true
                            }
                        )
                    }
                    .defaultMinSize(minWidth = ButtonHeight, minHeight = ButtonHeight)
                    .padding(horizontal = IconPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Download,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize),
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(LabelSpacing))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(end = LabelEndPadding)
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(hideLabel) },
                leadingIcon = { Icon(Close, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onHide()
                },
            )
        }
    }
}

/** How long a newly shown button keeps its label before collapsing to the icon. */
private const val LABEL_VISIBLE_MS = 3_000L

// Material 3 FAB / extended FAB metrics.
private val ButtonHeight = 56.dp
private val IconSize = 24.dp
private val IconPadding = 16.dp
private val LabelSpacing = 12.dp
private val LabelEndPadding = 4.dp
private val ButtonElevation = 6.dp
