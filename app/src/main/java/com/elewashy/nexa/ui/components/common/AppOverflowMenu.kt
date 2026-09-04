package com.elewashy.nexa.ui.components.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Shared Material 3 overflow surface.
 *
 * Normal callers use [DropdownMenu] and its automatic anchor, available-space, and RTL behavior.
 * The bottom browser toolbar can provide a dedicated [DropdownMenuPopupPositionProvider] without
 * changing positioning for any other menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    popupPositionProvider: DropdownMenuPopupPositionProvider? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (popupPositionProvider == null) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.widthIn(min = 160.dp, max = 280.dp),
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
        ) {
            AppOverflowMenuContent(content)
        }
        return
    }

    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = popupPositionProvider,
    ) {
        Surface(
            modifier = modifier.widthIn(min = 160.dp, max = 280.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AppOverflowMenuContent(content)
            }
        }
    }
}

@Composable
private fun ColumnScope.AppOverflowMenuContent(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(8.dp))
    content()
    Spacer(Modifier.height(8.dp))
}

/** A consistently padded action inside [AppOverflowMenu]. */
@Composable
fun AppOverflowMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        contentPadding = OverflowMenuItemPadding,
    )
}

private val OverflowMenuItemPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/** Bottom-toolbar-only provider: flush above the anchor and near the logical screen edge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberBottomBarOverflowMenuPositionProvider(
    edgeMargin: Dp = 8.dp,
): DropdownMenuPopupPositionProvider {
    val edgeMarginPx = with(LocalDensity.current) { edgeMargin.roundToPx() }
    return remember(edgeMarginPx) { BottomBarOverflowMenuPositionProvider(edgeMarginPx) }
}

@OptIn(ExperimentalMaterial3Api::class)
private class BottomBarOverflowMenuPositionProvider(
    private val edgeMarginPx: Int,
) : DropdownMenuPopupPositionProvider {
    override var transformOrigin: TransformOrigin by mutableStateOf(TransformOrigin.Center)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val offset = bottomBarOverflowMenuOffset(
            anchorBounds = anchorBounds,
            windowSize = windowSize,
            popupContentSize = popupContentSize,
            layoutDirection = layoutDirection,
            edgeMarginPx = edgeMarginPx,
        )
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2f
        transformOrigin = TransformOrigin(
            pivotFractionX = ((anchorCenterX - offset.x) /
                popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
            pivotFractionY = 1f,
        )
        return offset
    }
}

internal fun bottomBarOverflowMenuOffset(
    anchorBounds: IntRect,
    windowSize: IntSize,
    popupContentSize: IntSize,
    layoutDirection: LayoutDirection,
    edgeMarginPx: Int,
): IntOffset {
    val maximumX = (windowSize.width - popupContentSize.width - edgeMarginPx)
        .coerceAtLeast(edgeMarginPx)
    val x = if (layoutDirection == LayoutDirection.Ltr) maximumX else edgeMarginPx
    val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(edgeMarginPx)
    return IntOffset(x, y)
}
