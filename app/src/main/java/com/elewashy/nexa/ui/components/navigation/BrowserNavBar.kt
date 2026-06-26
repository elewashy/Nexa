package com.elewashy.nexa.ui.components.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.icons.ArrowBack
import com.elewashy.nexa.ui.icons.ArrowForward
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.Home
import com.elewashy.nexa.ui.icons.Language
import com.elewashy.nexa.ui.icons.Link
import com.elewashy.nexa.ui.icons.MoreHoriz
import com.elewashy.nexa.ui.icons.Refresh
import com.elewashy.nexa.ui.icons.Settings
import com.elewashy.nexa.ui.icons.Share

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserNavBar(
    state: BrowserNavBarState,
    onRefreshClick: () -> Unit,
    onLinkClick: () -> Unit,
    onHomeClick: () -> Unit,
    onMenuBackClick: () -> Unit,
    onMenuForwardClick: () -> Unit,
    onMenuShareClick: (String) -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUrlCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.toolbarVisible) return

    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val background = colors.surfaceContainer
    val dimensions = rememberBrowserNavBarDimensions()
    val adaptiveInfo = rememberAdaptiveLayoutInfo()

    Column(
        modifier = modifier
            .widthIn(max = if (adaptiveInfo.isTvLike) 720.dp else adaptiveInfo.contentMaxWidth)
            .fillMaxWidth()
            .background(background),
    ) {
        if (state.urlBarVisible) {
            UrlBar(
                initialText = state.urlText,
                primary = primary,
                onSurface = colors.onSurface,
                onSurfaceVariant = colors.onSurfaceVariant,
                dimensions = dimensions,
                onCommit = onUrlCommit,
            )
        }

        ProgressStrip(
            progressPercent = state.progressPercent,
            primary = primary,
            dimensions = dimensions,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.navBarHeight)
                .padding(horizontal = dimensions.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavAction(
                icon = Home,
                contentDescription = stringResource(R.string.home_btn),
                tint = primary,
                visible = state.homeVisible,
                dimensions = dimensions,
                onClick = onHomeClick,
            )
            NavAction(
                icon = Refresh,
                contentDescription = stringResource(R.string.refresh_btn),
                tint = primary,
                visible = state.refreshVisible,
                dimensions = dimensions,
                onClick = onRefreshClick,
            )
            NavAction(
                icon = Language,
                contentDescription = stringResource(R.string.search),
                tint = primary,
                visible = state.linkButtonVisible,
                dimensions = dimensions,
                onClick = onLinkClick,
            )
            if (state.moreOptionsVisible) {
                var menuExpanded by remember { mutableStateOf(false) }
                var menuOpenGeneration by remember { mutableIntStateOf(0) }
                LaunchedEffect(state.toolbarVisible, state.currentUrl) {
                    menuExpanded = false
                }
                Box {
                    IconButton(
                        onClick = {
                            menuOpenGeneration += 1
                            menuExpanded = true
                        },
                        modifier = Modifier.size(dimensions.actionSize)
                    ) {
                        Icon(
                            imageVector = MoreHoriz,
                            contentDescription = stringResource(R.string.more_options),
                            tint = primary,
                            modifier = Modifier.size(dimensions.iconSize)
                        )
                    }
                }
                if (menuExpanded) {
                    key(menuOpenGeneration) {
                        val sheetState = rememberBottomSheetState(
                            initialValue = SheetValue.Hidden,
                            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
                        )
                        ModalBottomSheet(
                            onDismissRequest = { menuExpanded = false },
                            sheetState = sheetState,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .widthIn(max = adaptiveInfo.sheetMaxWidth)
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OverflowActionButton(
                                            icon = ArrowBack,
                                            label = stringResource(R.string.back),
                                            enabled = state.backEnabled,
                                            onClick = { menuExpanded = false; onMenuBackClick() }
                                        )
                                        OverflowActionButton(
                                            icon = ArrowForward,
                                            label = stringResource(R.string.forward),
                                            enabled = state.forwardEnabled,
                                            onClick = { menuExpanded = false; onMenuForwardClick() }
                                        )
                                        OverflowActionButton(
                                            icon = Share,
                                            label = stringResource(R.string.share),
                                            enabled = state.currentUrl != null,
                                            onClick = {
                                                menuExpanded = false
                                                state.currentUrl?.let { onMenuShareClick(it) }
                                            }
                                        )
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.downloads)) },
                                        leadingIcon = {
                                            Icon(Download, contentDescription = null)
                                        },
                                        onClick = { menuExpanded = false; onDownloadsClick() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.settings)) },
                                        leadingIcon = {
                                            Icon(Settings, contentDescription = null)
                                        },
                                        onClick = { menuExpanded = false; onSettingsClick() }
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
private fun OverflowActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = OverflowActionShape,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
        )
    }
}

@Composable
private fun NavAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    dimensions: BrowserNavBarDimensions,
    enabled: Boolean = true,
    visible: Boolean = true,
) {
    if (!visible) {
        return
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(dimensions.actionSize)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(dimensions.iconSize),
        )
    }
}

@Composable
private fun ProgressStrip(
    progressPercent: Int?,
    primary: Color,
    dimensions: BrowserNavBarDimensions,
) {
    var lastPercent by remember { mutableIntStateOf(0) }
    if (progressPercent != null) lastPercent = progressPercent
    AnimatedVisibility(
        visible = progressPercent != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val value = lastPercent / 100f
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.progressHeight),
            color = primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            gapSize = 0.dp,
            drawStopIndicator = {},
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
    }
}

@Composable
private fun UrlBar(
    initialText: String,
    primary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    dimensions: BrowserNavBarDimensions,
    onCommit: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember(initialText) {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(0, initialText.length),
            )
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TextField(
        value = fieldValue,
        onValueChange = { fieldValue = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimensions.urlBarHorizontalPadding, vertical = dimensions.urlBarVerticalPadding)
            .height(dimensions.urlBarHeight)
            .clip(RoundedCornerShape(dimensions.urlBarRadius))
            .focusRequester(focusRequester),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Link,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(dimensions.urlIconSize),
            )
        },
        placeholder = {
            Text(
                text = stringResource(R.string.search_or_enter_address),
                color = onSurfaceVariant,
                maxLines = 1,
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = { onCommit(fieldValue.text) },
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedTextColor = onSurface,
            unfocusedTextColor = onSurface,
            cursorColor = primary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = primary,
            unfocusedLeadingIconColor = primary,
        ),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

private data class BrowserNavBarDimensions(
    val navBarHeight: Dp,
    val actionSize: Dp,
    val iconSize: Dp,
    val progressHeight: Dp,
    val horizontalPadding: Dp,
    val urlBarHeight: Dp,
    val urlBarRadius: Dp,
    val urlIconSize: Dp,
    val urlBarHorizontalPadding: Dp,
    val urlBarVerticalPadding: Dp,
)

@Composable
private fun rememberBrowserNavBarDimensions(): BrowserNavBarDimensions {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val screenWidth = adaptiveInfo.widthDp
    val screenHeight = adaptiveInfo.heightDp
    val compactHeight = screenHeight < COMPACT_HEIGHT_DP
    val expandedWidth = screenWidth >= EXPANDED_WIDTH_DP

    return remember(screenWidth, screenHeight) {
        BrowserNavBarDimensions(
            navBarHeight = when {
                adaptiveInfo.isTvLike -> 64.dp
                expandedWidth -> 56.dp
                else -> 52.dp
            },
            actionSize = when {
                compactHeight -> MIN_TOUCH_TARGET
                adaptiveInfo.isTvLike -> 56.dp
                expandedWidth -> 52.dp
                else -> MIN_TOUCH_TARGET
            },
            iconSize = when {
                compactHeight -> 20.dp
                adaptiveInfo.isTvLike -> 26.dp
                expandedWidth -> 24.dp
                else -> 22.dp
            },
            progressHeight = if (expandedWidth) 3.dp else 2.dp,
            horizontalPadding = when {
                expandedWidth -> 16.dp
                compactHeight -> 6.dp
                else -> 8.dp
            },
            urlBarHeight = when {
                compactHeight -> 46.dp
                expandedWidth -> 56.dp
                else -> 52.dp
            },
            urlBarRadius = if (expandedWidth) 14.dp else 12.dp,
            urlIconSize = if (compactHeight) 15.dp else 16.dp,
            urlBarHorizontalPadding = if (expandedWidth) 16.dp else 10.dp,
            urlBarVerticalPadding = if (compactHeight) 4.dp else 6.dp,
        )
    }
}

private const val COMPACT_HEIGHT_DP = 600
private const val EXPANDED_WIDTH_DP = 600
private val MIN_TOUCH_TARGET = 48.dp
private val OverflowActionShape: Shape = CutCornerShape(topStart = 14.dp, topEnd = 6.dp, bottomEnd = 14.dp, bottomStart = 6.dp)
private const val DISABLED_ALPHA = 0.35f
