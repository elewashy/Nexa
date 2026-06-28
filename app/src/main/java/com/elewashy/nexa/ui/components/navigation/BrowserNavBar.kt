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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
                LaunchedEffect(state.toolbarVisible, state.currentUrl) {
                    menuExpanded = false
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(dimensions.actionSize)
                    ) {
                        Icon(
                            imageVector = MoreHoriz,
                            contentDescription = stringResource(R.string.more_options),
                            tint = primary,
                            modifier = Modifier.size(dimensions.iconSize)
                        )
                    }

                    BrowserMoreOptionsMenu(
                        expanded = menuExpanded,
                        state = state,
                        onDismiss = { menuExpanded = false },
                        onBackClick = {
                            menuExpanded = false
                            onMenuBackClick()
                        },
                        onForwardClick = {
                            menuExpanded = false
                            onMenuForwardClick()
                        },
                        onShareClick = {
                            menuExpanded = false
                            state.currentUrl?.let { onMenuShareClick(it) }
                        },
                        onDownloadsClick = {
                            menuExpanded = false
                            onDownloadsClick()
                        },
                        onSettingsClick = {
                            menuExpanded = false
                            onSettingsClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun BrowserMoreOptionsMenu(
    expanded: Boolean,
    state: BrowserNavBarState,
    onDismiss: () -> Unit,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        BrowserMoreMenuPositionProvider(density = density)
    }

    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(BrowserMoreMenuWidth),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.downloads)) },
                    leadingIcon = {
                        Icon(Download, contentDescription = null)
                    },
                    onClick = onDownloadsClick,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings)) },
                    leadingIcon = {
                        Icon(Settings, contentDescription = null)
                    },
                    onClick = onSettingsClick,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverflowActionButton(
                        icon = ArrowBack,
                        label = stringResource(R.string.back),
                        enabled = state.backEnabled,
                        onClick = onBackClick,
                    )
                    OverflowActionButton(
                        icon = ArrowForward,
                        label = stringResource(R.string.forward),
                        enabled = state.forwardEnabled,
                        onClick = onForwardClick,
                    )
                    OverflowActionButton(
                        icon = Share,
                        label = stringResource(R.string.share),
                        enabled = state.currentUrl != null,
                        onClick = onShareClick,
                    )
                }
            }
        }
    }
}

// The standard DropdownMenu keeps a 48dp vertical window margin, which makes a bottom-bar menu
// float too far above its trigger. This provider keeps Material popup behavior but positions from
// the More button bounds directly.
private class BrowserMoreMenuPositionProvider(
    private val density: Density,
) : DropdownMenuPopupPositionProvider {
    override var transformOrigin by mutableStateOf(TransformOrigin.Center)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val edgeMarginPx = with(density) { BrowserMoreMenuEdgeMargin.roundToPx() }
        val anchorSpacingPx = with(density) { BrowserMoreMenuAnchorSpacing.roundToPx() }
        val horizontalNudgePx = with(density) { BrowserMoreMenuHorizontalNudge.roundToPx() }

        val x = calculateHorizontalPosition(
            anchorBounds = anchorBounds,
            windowSize = windowSize,
            popupContentSize = popupContentSize,
            layoutDirection = layoutDirection,
            edgeMarginPx = edgeMarginPx,
            horizontalNudgePx = horizontalNudgePx,
        )
        val y = calculateVerticalPosition(
            anchorBounds = anchorBounds,
            windowSize = windowSize,
            popupContentSize = popupContentSize,
            edgeMarginPx = edgeMarginPx,
            anchorSpacingPx = anchorSpacingPx,
        )

        val anchorCenter = Offset(anchorBounds.center.x.toFloat(), anchorBounds.center.y.toFloat())
        transformOrigin = TransformOrigin(
            pivotFractionX = ((anchorCenter.x - x) / popupContentSize.width).coerceIn(0f, 1f),
            pivotFractionY = ((anchorCenter.y - y) / popupContentSize.height).coerceIn(0f, 1f),
        )

        return IntOffset(x, y)
    }

    private fun calculateHorizontalPosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        popupContentSize: IntSize,
        layoutDirection: LayoutDirection,
        edgeMarginPx: Int,
        horizontalNudgePx: Int,
    ): Int {
        val anchorAlignedX = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width
            LayoutDirection.Rtl -> anchorBounds.left
        }
        val nudgeDirection = if (anchorBounds.center.x < windowSize.width / 2) -1 else 1
        val maxX = windowSize.width - edgeMarginPx - popupContentSize.width
        return (anchorAlignedX + nudgeDirection * horizontalNudgePx).coerceIn(
            minimumValue = edgeMarginPx,
            maximumValue = maxX.coerceAtLeast(edgeMarginPx),
        )
    }

    private fun calculateVerticalPosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        popupContentSize: IntSize,
        edgeMarginPx: Int,
        anchorSpacingPx: Int,
    ): Int {
        val preferredAboveAnchorY = anchorBounds.bottom - popupContentSize.height - anchorSpacingPx
        val maxY = windowSize.height - edgeMarginPx - popupContentSize.height
        return preferredAboveAnchorY.coerceIn(edgeMarginPx, maxY.coerceAtLeast(edgeMarginPx))
    }
}

@Composable
private fun OverflowActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
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
private val BrowserMoreMenuWidth = 248.dp
private val BrowserMoreMenuEdgeMargin = 8.dp
private val BrowserMoreMenuAnchorSpacing = 8.dp
private val BrowserMoreMenuHorizontalNudge = 28.dp
private const val DISABLED_ALPHA = 0.35f
