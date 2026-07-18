package com.elewashy.nexa.ui.adaptive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import kotlin.math.floor

@Immutable
data class AdaptiveLayoutInfo(
    val widthDp: Int,
    val heightDp: Int,
    val windowSizeClass: WindowSizeClass,
    val isLandscape: Boolean,
    val isCompact: Boolean,
    val isMedium: Boolean,
    val isExpanded: Boolean,
    val isTvLike: Boolean,
    val horizontalPadding: Dp,
    val paneSpacing: Dp,
    val contentMaxWidth: Dp,
    val dialogMaxWidth: Dp,
    val sheetMaxWidth: Dp,
    val listMaxWidth: Dp,
    val gridMinCellWidth: Dp,
) {
    val widthClass: WindowWidthSizeClass get() = windowSizeClass.widthSizeClass
    val heightClass: WindowHeightSizeClass get() = windowSizeClass.heightSizeClass
    val useSideNavigation: Boolean = isExpanded || (isMedium && isLandscape) || isTvLike
    val useTwoPane: Boolean = isExpanded && widthDp >= 900
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberAdaptiveLayoutInfo(): AdaptiveLayoutInfo {
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val widthDp = with(density) { containerSize.width.toDp().value.toInt() }
    val heightDp = with(density) { containerSize.height.toDp().value.toInt() }
    val isLandscape = widthDp >= heightDp

    return remember(widthDp, heightDp, isLandscape) {
        val windowSizeClass = WindowSizeClass.calculateFromSize(
            size = DpSize(widthDp.dp, heightDp.dp),
        )
        val widthClass = windowSizeClass.widthSizeClass
        val isTvLike = widthDp >= 960 && isLandscape
        val horizontalPadding = when (widthClass) {
            WindowWidthSizeClass.Compact -> 16.dp
            WindowWidthSizeClass.Medium -> 24.dp
            else -> if (isTvLike) 48.dp else 32.dp
        }
        AdaptiveLayoutInfo(
            widthDp = widthDp,
            heightDp = heightDp,
            windowSizeClass = windowSizeClass,
            isLandscape = isLandscape,
            isCompact = widthClass == WindowWidthSizeClass.Compact,
            isMedium = widthClass == WindowWidthSizeClass.Medium,
            isExpanded = widthClass == WindowWidthSizeClass.Expanded,
            isTvLike = isTvLike,
            horizontalPadding = horizontalPadding,
            paneSpacing = if (isTvLike) 32.dp else 24.dp,
            contentMaxWidth = when (widthClass) {
                WindowWidthSizeClass.Compact -> 560.dp
                WindowWidthSizeClass.Medium -> 640.dp
                else -> if (isTvLike) 1040.dp else 920.dp
            },
            dialogMaxWidth = if (isTvLike) 720.dp else 560.dp,
            sheetMaxWidth = when (widthClass) {
                WindowWidthSizeClass.Compact -> 560.dp
                WindowWidthSizeClass.Medium -> 640.dp
                else -> 720.dp
            },
            listMaxWidth = when (widthClass) {
                WindowWidthSizeClass.Compact -> 560.dp
                WindowWidthSizeClass.Medium -> 680.dp
                else -> 960.dp
            },
            gridMinCellWidth = when (widthClass) {
                WindowWidthSizeClass.Compact -> 160.dp
                WindowWidthSizeClass.Medium -> 220.dp
                else -> if (isTvLike) 280.dp else 240.dp
            },
        )
    }
}

@Composable
fun safeDrawingPaddingValues(
    horizontal: Dp = 0.dp,
    vertical: Dp = 0.dp,
): PaddingValues {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    return PaddingValues(
        start = with(density) { insets.getLeft(this, direction).toDp() } + horizontal,
        top = with(density) { insets.getTop(this).toDp() } + vertical,
        end = with(density) { insets.getRight(this, direction).toDp() } + horizontal,
        bottom = with(density) { insets.getBottom(this).toDp() } + vertical,
    )
}

@Composable
fun PaddingValues.plusAdaptiveHorizontal(extra: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + extra,
        top = calculateTopPadding(),
        end = calculateEndPadding(direction) + extra,
        bottom = calculateBottomPadding(),
    )
}

fun adaptiveGridColumns(availableWidth: Dp, minCellWidth: Dp, minColumns: Int = 1, maxColumns: Int = 6): Int {
    if (availableWidth == Dp.Unspecified || availableWidth <= 0.dp) return minColumns
    val count = floor((availableWidth / minCellWidth)).toInt()
    return count.coerceIn(minColumns, maxColumns)
}

fun Modifier.thenIf(condition: Boolean, modifier: Modifier): Modifier = if (condition) then(modifier) else this
