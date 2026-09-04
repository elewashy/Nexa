package com.elewashy.nexa.ui.components.common

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import com.elewashy.nexa.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/** App-wide transient-message host with reversible, direction-independent swipe dismissal. */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    navigationBarPadding: Boolean = true,
) {
    LaunchedEffect(hostState) {
        AppMessages.messages.collect { message ->
            hostState.showSnackbar(
                message = message.message,
                actionLabel = message.actionLabel,
                duration = message.duration,
            )
        }
    }

    val hostModifier = modifier
        .padding(start = HorizontalMargin, end = HorizontalMargin, bottom = bottomPadding)
        .let { if (navigationBarPadding) it.navigationBarsPadding() else it }

    SnackbarHost(hostState = hostState, modifier = hostModifier) { data ->
        InteractiveSnackbar(data)
    }
}

/**
 * Follows the pointer in two dimensions, then either settles to its origin or dismisses.
 * `draggable2D` supplies touch-slop and velocity handling, while translation stays in the draw
 * phase so dragging never triggers measurement or placement work.
 */
@Composable
private fun InteractiveSnackbar(data: SnackbarData) {
    val dismissLabel = stringResource(R.string.dismiss)
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var dragOffset by remember(data) { mutableStateOf(Offset.Zero) }
    var snackbarSize by remember(data) { mutableStateOf(IntSize.Zero) }
    var settleJob by remember(data) { mutableStateOf<Job?>(null) }
    var isSettling by remember(data) { mutableStateOf(false) }

    val minimumFlingVelocity = remember(view, density) {
        max(
            ViewConfiguration.get(view.context).scaledMinimumFlingVelocity.toFloat(),
            with(density) { MinimumIntentionalFlingVelocity.toPx() },
        )
    }
    val draggableState = rememberDraggable2DState { delta -> dragOffset += delta }

    DisposableEffect(data) {
        onDispose { settleJob?.cancel() }
    }

    Snackbar(
        snackbarData = data,
        modifier = Modifier
            .onSizeChanged { snackbarSize = it }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                val progress = snackbarDismissProgress(dragOffset, snackbarSize)
                alpha = 1f - (progress.coerceIn(0f, 1f) * MaximumDragFade)
                val dragScale = 1f - (progress.coerceIn(0f, 1f) * MaximumDragScaleReduction)
                scaleX = dragScale
                scaleY = dragScale
            }
            .draggable2D(
                state = draggableState,
                startDragImmediately = isSettling,
                onDragStarted = {
                    settleJob?.cancel()
                    settleJob = null
                    isSettling = false
                },
                onDragStopped = { velocity ->
                    val releaseOffset = dragOffset
                    val dismiss = shouldDismissSnackbar(
                        offset = releaseOffset,
                        velocity = velocity,
                        size = snackbarSize,
                        minimumFlingVelocity = minimumFlingVelocity,
                    )
                    settleJob = scope.launch {
                        isSettling = true
                        val animation = Animatable(releaseOffset, Offset.VectorConverter)
                        if (dismiss) {
                            val target = snackbarDismissTarget(
                                offset = releaseOffset,
                                velocity = velocity,
                                size = snackbarSize,
                            )
                            animation.animateTo(
                                targetValue = target,
                                animationSpec = tween(
                                    durationMillis = DismissAnimationDurationMillis,
                                    easing = FastOutLinearInEasing,
                                ),
                            ) { dragOffset = value }
                            data.dismiss()
                        } else {
                            animation.animateTo(
                                targetValue = Offset.Zero,
                                animationSpec = spring<Offset>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) { dragOffset = value }
                            dragOffset = Offset.Zero
                        }
                        isSettling = false
                        settleJob = null
                    }
                },
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(dismissLabel) {
                        settleJob?.cancel()
                        data.dismiss()
                        true
                    },
                )
            },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        actionColor = MaterialTheme.colorScheme.primary,
        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun snackbarDismissProgress(offset: Offset, size: IntSize): Float {
    if (size.width <= 0 || size.height <= 0) return 0f
    val horizontal = offset.x / (size.width * HorizontalDismissFraction)
    val vertical = offset.y / (size.height * VerticalDismissFraction)
    return hypot(horizontal, vertical)
}

internal fun shouldDismissSnackbar(
    offset: Offset,
    velocity: Velocity,
    size: IntSize,
    minimumFlingVelocity: Float,
): Boolean {
    if (size.width <= 0 || size.height <= 0) return false
    if (snackbarDismissProgress(offset, size) >= 1f) return true

    val speed = hypot(velocity.x, velocity.y)
    if (speed < minimumFlingVelocity) return false
    val distance = offset.getDistance()
    if (distance == 0f) return true

    // A fast gesture only dismisses while still travelling away from the origin. A reversal near
    // release therefore restores the Snackbar instead of accidentally dismissing it.
    val outwardVelocity = (offset.x * velocity.x + offset.y * velocity.y) / distance
    return outwardVelocity >= minimumFlingVelocity
}

internal fun snackbarDismissTarget(
    offset: Offset,
    velocity: Velocity,
    size: IntSize,
): Offset {
    val directionSource = if (offset.getDistance() > 0f) {
        offset
    } else {
        Offset(velocity.x, velocity.y)
    }
    val magnitude = directionSource.getDistance().takeIf { it > 0f } ?: 1f
    val distance = hypot(size.width.toFloat(), size.height.toFloat()).coerceAtLeast(1f) * 1.15f
    return Offset(
        x = directionSource.x / magnitude * distance,
        y = directionSource.y / magnitude * distance,
    )
}

private const val HorizontalDismissFraction = 0.4f
private const val VerticalDismissFraction = 0.65f
private const val MaximumDragFade = 0.18f
private const val MaximumDragScaleReduction = 0.02f
private const val DismissAnimationDurationMillis = 160
private val MinimumIntentionalFlingVelocity = 800.dp
private val HorizontalMargin = 12.dp
