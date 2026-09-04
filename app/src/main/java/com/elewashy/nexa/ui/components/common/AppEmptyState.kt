package com.elewashy.nexa.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon

/**
 * App-wide empty-state treatment.
 *
 * Material does not define a dedicated empty-state component or mandatory dimensions. This uses
 * semantic Material typography and colors while adapting its decorative geometry to the space
 * actually available to the component. Text keeps its semantic type scale and remains scrollable
 * under extreme height or font-scale constraints instead of being covered or clipped.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    // Material 3's RoundedPolygon.toShape() expects normalized 0..1 geometry. A default
    // RoundedPolygon spans roughly -1..1; passing it through directly doubles the requested
    // layout size and lets the outline paint beyond its container.
    val polygon = remember { createEmptyStatePolygon() }
    val polygonShape = polygon.toShape(startAngle = EmptyStatePolygonRotationDegrees)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dimensions = emptyStateDimensions(maxWidth, maxHeight)
        val boundedHeight = if (maxHeight == Dp.Infinity) {
            Modifier
        } else {
            Modifier.heightIn(max = maxHeight)
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(
                animationSpec = tween(EmptyStateEnterDurationMillis),
            ) + scaleIn(
                initialScale = EmptyStateInitialScale,
                animationSpec = tween(
                    durationMillis = EmptyStateEnterDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            ),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = dimensions.contentMaxWidth)
                    .fillMaxWidth()
                    .then(boundedHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = dimensions.horizontalPadding,
                        vertical = dimensions.verticalPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(dimensions.badgeSize),
                    shape = polygonShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(dimensions.iconSize),
                        )
                    }
                }
                Spacer(Modifier.height(dimensions.iconTitleSpacing))
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(EmptyStateTitleBodySpacing))
                    Text(
                        text = description,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Immutable
internal data class EmptyStateDimensions(
    val badgeSize: Dp,
    val iconSize: Dp,
    val iconTitleSpacing: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val contentMaxWidth: Dp,
)

/** Uses local constraints because split-screen, freeform windows, and panes can differ from the device size. */
internal fun emptyStateDimensions(availableWidth: Dp, availableHeight: Dp): EmptyStateDimensions =
    when {
        availableWidth < CompactWidthThreshold || availableHeight < CompactHeightThreshold ->
            EmptyStateDimensions(
                badgeSize = 56.dp,
                iconSize = 26.dp,
                iconTitleSpacing = 14.dp,
                horizontalPadding = 16.dp,
                verticalPadding = 16.dp,
                contentMaxWidth = 360.dp,
            )
        availableWidth >= ExpandedWidthThreshold && availableHeight >= ExpandedHeightThreshold ->
            EmptyStateDimensions(
                badgeSize = 96.dp,
                iconSize = 44.dp,
                iconTitleSpacing = 24.dp,
                horizontalPadding = 32.dp,
                verticalPadding = 32.dp,
                contentMaxWidth = 560.dp,
            )
        else -> EmptyStateDimensions(
            badgeSize = 80.dp,
            iconSize = 36.dp,
            iconTitleSpacing = 20.dp,
            horizontalPadding = 24.dp,
            verticalPadding = 24.dp,
            contentMaxWidth = 480.dp,
        )
    }

internal fun createEmptyStatePolygon(): RoundedPolygon = RoundedPolygon(
    numVertices = EmptyStatePolygonVertices,
    rounding = CornerRounding(
        radius = EmptyStateCornerRadius,
        smoothing = EmptyStateCornerSmoothing,
    ),
).normalized()

private const val EmptyStatePolygonVertices = 6
private const val EmptyStateCornerRadius = 0.24f
private const val EmptyStateCornerSmoothing = 0.1f
private const val EmptyStatePolygonRotationDegrees = 30
private const val EmptyStateEnterDurationMillis = 220
private const val EmptyStateInitialScale = 0.96f
private val CompactWidthThreshold = 320.dp
private val CompactHeightThreshold = 400.dp
private val ExpandedWidthThreshold = 840.dp
private val ExpandedHeightThreshold = 600.dp
private val EmptyStateTitleBodySpacing = 8.dp
