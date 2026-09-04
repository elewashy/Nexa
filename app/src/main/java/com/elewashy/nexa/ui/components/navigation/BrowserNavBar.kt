package com.elewashy.nexa.ui.components.navigation

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.core.util.UrlDisplay
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppOverflowMenu
import com.elewashy.nexa.ui.components.common.AppOverflowMenuItem
import com.elewashy.nexa.ui.components.common.AppTabCountIcon
import com.elewashy.nexa.ui.components.common.rememberBottomBarOverflowMenuPositionProvider
import com.elewashy.nexa.ui.components.common.SiteFavicon
import com.elewashy.nexa.ui.icons.Add
import com.elewashy.nexa.ui.icons.ArrowBack
import com.elewashy.nexa.ui.icons.ArrowForward
import com.elewashy.nexa.ui.icons.Bookmark
import com.elewashy.nexa.ui.icons.BookmarkBorder
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.History
import com.elewashy.nexa.ui.icons.Home
import com.elewashy.nexa.ui.icons.MoreHoriz
import com.elewashy.nexa.ui.icons.MoreVert
import com.elewashy.nexa.ui.icons.Refresh
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.Settings
import com.elewashy.nexa.ui.icons.Share

/**
 * Compact bottom action bar. The search action toggles an inline address
 * preview ([onToggleAddressPreview]); tapping the preview opens the omnibox via
 * [BrowserNavBarActions.onOpenSearch] and its close button calls
 * [onDismissAddressPreview].
 */
@Composable
fun BrowserNavBar(
    state: BrowserNavBarState,
    pageFavicon: Bitmap?,
    actions: BrowserNavBarActions,
    onToggleAddressPreview: () -> Unit,
    onDismissAddressPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.toolbarVisible) return

    val palette = browserToolbarPalette(state.isPrivate)
    val dimensions = rememberBrowserNavBarDimensions()
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val bottomMenuPositionProvider = rememberBottomBarOverflowMenuPositionProvider()

    Column(
        modifier = modifier
            .widthIn(max = if (adaptiveInfo.isTvLike) 720.dp else adaptiveInfo.contentMaxWidth)
            .fillMaxWidth()
            .background(palette.container),
    ) {
        BrowserNavigationProgress(progressPercent = state.progressPercent)

        AnimatedContent(
            targetState = state.addressPreviewVisible,
            transitionSpec = {
                (fadeIn(tween(180)) + expandHorizontally(expandFrom = Alignment.CenterHorizontally))
                    .togetherWith(
                        fadeOut(tween(120)) + shrinkHorizontally(shrinkTowards = Alignment.CenterHorizontally)
                    )
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.Center,
            label = "browserAddressPreview",
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.navBarHeight),
        ) { previewVisible ->
            if (previewVisible) {
                BrowserAddressPreview(
                    currentUrl = state.urlText,
                    pageFavicon = pageFavicon,
                    isPrivate = state.isPrivate,
                    onUrlClick = actions.onOpenSearch,
                    onDismiss = onDismissAddressPreview,
                    modifier = Modifier.padding(horizontal = dimensions.horizontalPadding),
                )
            } else {
                BrowserNavActions(
                    state = state,
                    vertical = false,
                    menuPositionProvider = bottomMenuPositionProvider,
                    dimensions = dimensions,
                    primary = palette.content,
                    actions = actions,
                    onSearchActionClick = onToggleAddressPreview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensions.navBarHeight)
                        .padding(horizontal = dimensions.horizontalPadding),
                )
            }
        }
    }
}

/** Compact top toolbar modeled after a conventional browser address row. */
@Composable
fun BrowserTopNavBar(
    state: BrowserNavBarState,
    pageFavicon: Bitmap?,
    actions: BrowserNavBarActions,
    modifier: Modifier = Modifier,
) {
    if (!state.toolbarVisible) return

    val dimensions = rememberBrowserNavBarDimensions()
    val palette = browserToolbarPalette(state.isPrivate)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.container,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensions.navBarHeight)
                    .padding(horizontal = dimensions.horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NavAction(
                    icon = Home,
                    contentDescription = stringResource(R.string.home_btn),
                    tint = palette.content,
                    dimensions = dimensions,
                    visible = state.homeVisible,
                    onClick = actions.onHome,
                )
                BrowserAddressPill(
                    currentUrl = state.urlText,
                    pageFavicon = pageFavicon,
                    isPrivate = state.isPrivate,
                    compactLabel = true,
                    onClick = actions.onOpenSearch,
                    modifier = Modifier.weight(1f),
                )
                NavAction(
                    icon = Add,
                    contentDescription = stringResource(R.string.new_tab),
                    tint = palette.content,
                    dimensions = dimensions,
                    onClick = actions.onNewTab,
                )
                TabCountAction(
                    tabCount = state.tabCount,
                    tint = palette.content,
                    dimensions = dimensions,
                    onClick = actions.onTabs,
                )
                if (state.moreOptionsVisible) {
                    MoreOptionsAction(
                        state = state,
                        primary = palette.content,
                        dimensions = dimensions,
                        icon = MoreVert,
                        pageActionsPlacement = BrowserMenuPageActionsPlacement.First,
                        actions = actions,
                    )
                }
            }
            BrowserNavigationProgress(progressPercent = state.progressPercent)
        }
    }
}

/** Toolbar colors: private mode uses a fixed dark palette regardless of theme. */
private class BrowserToolbarPalette(val container: Color, val content: Color)

/**
 * Not remembered on purpose: [MaterialTheme.colorScheme] fields are snapshot
 * state that can be updated in place, so caching would pin stale colors.
 */
@Composable
private fun browserToolbarPalette(isPrivate: Boolean): BrowserToolbarPalette {
    val colors = MaterialTheme.colorScheme
    return if (isPrivate) {
        BrowserToolbarPalette(PrivateToolbarContainer, PrivateToolbarContent)
    } else {
        BrowserToolbarPalette(colors.surfaceContainer, colors.primary)
    }
}

@Composable
private fun BrowserAddressPill(
    currentUrl: String,
    pageFavicon: Bitmap?,
    isPrivate: Boolean,
    compactLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isPrivate) PrivateAddressContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (isPrivate) PrivateToolbarContent
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SiteFavicon(
                pageUrl = currentUrl,
                runtimeBitmap = pageFavicon,
                allowPersistentLookup = !isPrivate,
                size = 20.dp,
            )
            Text(
                text = when {
                    currentUrl.isBlank() -> stringResource(R.string.search_or_enter_address)
                    compactLabel -> remember(currentUrl) { UrlDisplay.hostAndPath(currentUrl) }
                    else -> currentUrl
                },
                modifier = Modifier.weight(1f),
                color = contentColor,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BrowserAddressPreview(
    currentUrl: String,
    pageFavicon: Bitmap?,
    isPrivate: Boolean,
    onUrlClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BrowserAddressPill(
            currentUrl = currentUrl,
            pageFavicon = pageFavicon,
            isPrivate = isPrivate,
            compactLabel = false,
            onClick = onUrlClick,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(MIN_TOUCH_TARGET)) {
            Icon(
                imageVector = Close,
                contentDescription = stringResource(R.string.close_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Left-side adaptive navigation for medium and expanded browser windows.
 * Renders the exact same actions as the compact bottom bar so behavior and
 * styling stay identical across window sizes; only the axis changes.
 */
@Composable
fun BrowserNavigationRail(
    state: BrowserNavBarState,
    actions: BrowserNavBarActions,
    modifier: Modifier = Modifier,
) {
    if (!state.toolbarVisible) return

    val palette = browserToolbarPalette(state.isPrivate)
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(BROWSER_RAIL_WIDTH),
        color = palette.container,
    ) {
        BrowserNavActions(
            state = state,
            vertical = true,
            menuPositionProvider = null,
            dimensions = rememberBrowserNavBarDimensions(),
            primary = palette.content,
            actions = actions,
            onSearchActionClick = actions.onOpenSearch,
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = RAIL_VERTICAL_PADDING),
        )
    }
}

/**
 * Single implementation of the browser actions (home, refresh, search, more)
 * shared by the compact bottom bar and the large-screen side rail. Browser
 * controls are actions rather than top-level destinations, so icon buttons are
 * used instead of NavigationBar/NavigationRail items.
 */
@Composable
private fun BrowserNavActions(
    state: BrowserNavBarState,
    vertical: Boolean,
    menuPositionProvider: DropdownMenuPopupPositionProvider?,
    dimensions: BrowserNavBarDimensions,
    primary: Color,
    actions: BrowserNavBarActions,
    onSearchActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionContent: @Composable () -> Unit = {
        NavAction(
            icon = Home,
            contentDescription = stringResource(R.string.home_btn),
            tint = primary,
            visible = state.homeVisible,
            dimensions = dimensions,
            onClick = actions.onHome,
        )
        NavAction(
            icon = Refresh,
            contentDescription = stringResource(R.string.refresh_btn),
            tint = primary,
            visible = state.refreshVisible,
            dimensions = dimensions,
            onClick = actions.onRefresh,
        )
        NavAction(
            icon = Search,
            contentDescription = stringResource(R.string.search),
            tint = primary,
            visible = state.linkButtonVisible,
            dimensions = dimensions,
            onClick = onSearchActionClick,
        )
        TabCountAction(
            tabCount = state.tabCount,
            tint = primary,
            dimensions = dimensions,
            onClick = actions.onTabs,
        )
        if (state.moreOptionsVisible) {
            MoreOptionsAction(
                state = state,
                primary = primary,
                dimensions = dimensions,
                icon = MoreHoriz,
                menuPositionProvider = menuPositionProvider,
                actions = actions,
            )
        }
    }

    if (vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RAIL_ACTION_SPACING, Alignment.CenterVertically),
        ) {
            actionContent()
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            actionContent()
        }
    }
}

@Composable
private fun MoreOptionsAction(
    state: BrowserNavBarState,
    primary: Color,
    dimensions: BrowserNavBarDimensions,
    icon: ImageVector,
    pageActionsPlacement: BrowserMenuPageActionsPlacement = BrowserMenuPageActionsPlacement.Last,
    menuPositionProvider: DropdownMenuPopupPositionProvider? = null,
    actions: BrowserNavBarActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.toolbarVisible, state.currentUrl) {
        menuExpanded = false
    }
    Box {
        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(dimensions.actionSize)) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.more_options),
                tint = primary,
                modifier = Modifier.size(dimensions.iconSize),
            )
        }
        BrowserMoreOptionsMenu(
            expanded = menuExpanded,
            state = state,
            pageActionsPlacement = pageActionsPlacement,
            menuPositionProvider = menuPositionProvider,
            onDismiss = { menuExpanded = false },
            onBackClick = { menuExpanded = false; actions.onBack() },
            onForwardClick = { menuExpanded = false; actions.onForward() },
            onShareClick = {
                menuExpanded = false
                state.currentUrl?.let(actions.onShare)
            },
            onNewTabClick = { menuExpanded = false; actions.onNewTab() },
            onBookmarksClick = { menuExpanded = false; actions.onBookmarks() },
            onToggleBookmarkClick = { menuExpanded = false; actions.onToggleBookmark() },
            onDownloadsClick = { menuExpanded = false; actions.onDownloads() },
            onHistoryClick = { menuExpanded = false; actions.onHistory() },
            onSettingsClick = { menuExpanded = false; actions.onSettings() },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun BrowserMoreOptionsMenu(
    expanded: Boolean,
    state: BrowserNavBarState,
    pageActionsPlacement: BrowserMenuPageActionsPlacement,
    menuPositionProvider: DropdownMenuPopupPositionProvider?,
    onDismiss: () -> Unit,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onShareClick: () -> Unit,
    onNewTabClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onToggleBookmarkClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    AppOverflowMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(BrowserMoreMenuWidth),
        popupPositionProvider = menuPositionProvider,
    ) {
        if (pageActionsPlacement == BrowserMenuPageActionsPlacement.First) {
            BrowserPageActionsRow(
                state = state,
                onBackClick = onBackClick,
                onForwardClick = onForwardClick,
                onToggleBookmarkClick = onToggleBookmarkClick,
                onShareClick = onShareClick,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        AppOverflowMenuItem(
            text = stringResource(R.string.new_tab),
            leadingIcon = { Icon(Add, contentDescription = null) },
            onClick = onNewTabClick,
        )
        AppOverflowMenuItem(
            text = stringResource(R.string.bookmarks),
            leadingIcon = { Icon(BookmarkBorder, contentDescription = null) },
            onClick = onBookmarksClick,
        )
        AppOverflowMenuItem(
            text = stringResource(R.string.history),
            leadingIcon = { Icon(History, contentDescription = null) },
            onClick = onHistoryClick,
        )
        AppOverflowMenuItem(
            text = stringResource(R.string.downloads),
            leadingIcon = { Icon(Download, contentDescription = null) },
            onClick = onDownloadsClick,
        )
        AppOverflowMenuItem(
            text = stringResource(R.string.settings),
            leadingIcon = { Icon(Settings, contentDescription = null) },
            onClick = onSettingsClick,
        )
        if (pageActionsPlacement == BrowserMenuPageActionsPlacement.Last) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            BrowserPageActionsRow(
                state = state,
                onBackClick = onBackClick,
                onForwardClick = onForwardClick,
                onToggleBookmarkClick = onToggleBookmarkClick,
                onShareClick = onShareClick,
            )
        }
    }
}

@Composable
private fun BrowserPageActionsRow(
    state: BrowserNavBarState,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onToggleBookmarkClick: () -> Unit,
    onShareClick: () -> Unit,
) {
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
            icon = if (state.isCurrentPageBookmarked) Bookmark else BookmarkBorder,
            label = stringResource(
                if (state.isCurrentPageBookmarked) R.string.remove_bookmark
                else R.string.bookmark_this_page
            ),
            enabled = state.canBookmarkCurrentPage,
            onClick = onToggleBookmarkClick,
        )
        OverflowActionButton(
            icon = Share,
            label = stringResource(R.string.share),
            enabled = state.currentUrl != null,
            onClick = onShareClick,
        )
    }
}

/**
 * Tab switcher entry point: shows the workspace tab count inside a rounded
 * outline (the classic browser affordance).
 */
@Composable
private fun TabCountAction(
    tabCount: Int,
    tint: Color,
    dimensions: BrowserNavBarDimensions,
    onClick: () -> Unit,
) {
    val accessibilityLabel = stringResource(R.string.tabs_count_accessibility, tabCount)
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(dimensions.actionSize)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        AppTabCountIcon(
            count = tabCount,
            color = tint,
            size = dimensions.iconSize,
        )
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
fun BrowserNavigationProgress(
    progressPercent: Int?,
    modifier: Modifier = Modifier,
) {
    val percent = progressPercent?.coerceIn(1, 99) ?: return
    val dimensions = rememberBrowserNavBarDimensions()
    val primary = MaterialTheme.colorScheme.primary
    val animatedProgress by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 120),
        label = "pageLoadProgress",
    )
    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .height(dimensions.progressHeight),
        color = primary,
        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        gapSize = 0.dp,
        drawStopIndicator = {},
        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    )
}

internal data class BrowserNavBarDimensions(
    val navBarHeight: Dp,
    val actionSize: Dp,
    val iconSize: Dp,
    val progressHeight: Dp,
    val horizontalPadding: Dp,
)

@Composable
private fun rememberBrowserNavBarDimensions(): BrowserNavBarDimensions {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val screenWidth = adaptiveInfo.widthDp
    val screenHeight = adaptiveInfo.heightDp

    return remember(screenWidth, screenHeight, adaptiveInfo.isTvLike) {
        browserNavBarDimensionsFor(
            screenWidthDp = screenWidth,
            screenHeightDp = screenHeight,
            isTvLike = adaptiveInfo.isTvLike,
        )
    }
}

internal fun browserNavBarDimensionsFor(
    screenWidthDp: Int,
    screenHeightDp: Int,
    isTvLike: Boolean,
): BrowserNavBarDimensions {
    val compactHeight = screenHeightDp < COMPACT_HEIGHT_DP
    val expandedWidth = screenWidthDp >= EXPANDED_WIDTH_DP
    return BrowserNavBarDimensions(
        navBarHeight = when {
            isTvLike -> 64.dp
            expandedWidth -> 56.dp
            else -> 52.dp
        },
        actionSize = when {
            compactHeight -> MIN_TOUCH_TARGET
            isTvLike -> 56.dp
            expandedWidth -> 52.dp
            else -> MIN_TOUCH_TARGET
        },
        iconSize = when {
            compactHeight -> 20.dp
            isTvLike -> 26.dp
            expandedWidth -> 24.dp
            else -> 22.dp
        },
        progressHeight = if (expandedWidth) 3.dp else 2.dp,
        horizontalPadding = when {
            expandedWidth -> 16.dp
            compactHeight -> 6.dp
            else -> 8.dp
        },
    )
}

private enum class BrowserMenuPageActionsPlacement { First, Last }

private const val COMPACT_HEIGHT_DP = 600
private const val EXPANDED_WIDTH_DP = 600
private val MIN_TOUCH_TARGET = 48.dp
private val BROWSER_RAIL_WIDTH = 80.dp
private val RAIL_VERTICAL_PADDING = 16.dp
private val RAIL_ACTION_SPACING = 8.dp
private val BrowserMoreMenuWidth = 248.dp
private const val DISABLED_ALPHA = 0.35f
private val PrivateToolbarContainer = Color(0xFF202124)
private val PrivateAddressContainer = Color(0xFF303134)
private val PrivateToolbarContent = Color(0xFFE8EAED)
