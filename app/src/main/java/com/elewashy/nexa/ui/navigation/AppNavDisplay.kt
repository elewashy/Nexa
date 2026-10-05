package com.elewashy.nexa.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent

/**
 * The app's single navigation host (Navigation 3).
 *
 * - Each entry gets its own saveable-state holder and ViewModel store: `rememberSaveable` state
 *   survives while the page is in the back stack (and across process death), and the page's
 *   ViewModels are cleared exactly when it is popped.
 * - Back is handled by [NavDisplay] through the AndroidX NavigationEvent dispatcher, which drives
 *   the predictive back preview on Android 14+ and falls back to a regular pop animation on
 *   older versions, 3-button navigation and keyboard/toolbar back.
 *
 * Motion: pages that cover the browser fade through, so the WebView surface is never translated
 * or scaled; pages inside a feature use a horizontal shared axis. The predictive back preview is
 * a short, linear transition: it tracks the finger 1:1 while dragging, and on release only the
 * remaining fraction of [PREDICTIVE_BACK_DURATION_MS] plays, so a fast swipe finishes almost
 * immediately instead of replaying a long ease-in animation. A swipe released before the
 * preview starts (or a back key/button) uses the short pop transitions.
 */
@Composable
fun AppNavDisplay(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    entries: EntryProviderScope<AppRoute>.() -> Unit,
) {
    NexaNavDisplay(
        backStack = navigator.backStack,
        onBack = navigator::back,
        modifier = modifier,
        entries = entries,
    )
}

/**
 * [AppNavDisplay] for any back stack of serializable keys, so a self-contained flow (onboarding)
 * gets the same per-entry saved state, per-entry ViewModels, predictive back and motion as the
 * app's main stack. [onBack] is only called while there is a page to pop; at the root the
 * system owns Back.
 */
@Composable
fun <T : NavKey> NexaNavDisplay(
    backStack: NavBackStack<T>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    entries: EntryProviderScope<T>.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = onBack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { forwardTransition(direction) },
        popTransitionSpec = { popTransition(direction) },
        predictivePopTransitionSpec = { swipeEdge -> predictivePopTransition(swipeEdge, direction) },
        entryProvider = entryProvider(builder = entries),
    )
}

/** Marks the entry that hosts the browser's WebView (see [AppNavDisplay] motion). */
private data object HostsWebView : NavMetadataKey<Boolean>

/** Metadata for the browser entry. */
val BrowserEntryMetadata: Map<String, Any> = metadata { put(HostsWebView, true) }

private fun Scene<*>.hostsWebView(): Boolean = HostsWebView in metadata

private fun AnimatedContentTransitionScope<out Scene<*>>.coversBrowser(): Boolean =
    initialState.hostsWebView() || targetState.hostsWebView()

private fun AnimatedContentTransitionScope<out Scene<*>>.forwardTransition(
    direction: LayoutDirection,
): ContentTransform = if (coversBrowser()) {
    fadeThrough()
} else {
    slideInHorizontally(tween(SHARED_AXIS_DURATION_MS, easing = FastOutSlowInEasing)) { width ->
        direction.sign * width
    } + fadeIn(tween(SHARED_AXIS_FADE_DURATION_MS, easing = LinearOutSlowInEasing)) togetherWith
        slideOutHorizontally(tween(SHARED_AXIS_DURATION_MS, easing = FastOutSlowInEasing)) { width ->
            -direction.sign * width / SHARED_AXIS_PARALLAX_DIVISOR
        } + fadeOut(tween(SHARED_AXIS_FADE_DURATION_MS, easing = LinearEasing))
}

private fun AnimatedContentTransitionScope<out Scene<*>>.popTransition(
    direction: LayoutDirection,
): ContentTransform = if (coversBrowser()) {
    fadeThrough(outMillis = POP_FADE_OUT_DURATION_MS, inMillis = POP_FADE_IN_DURATION_MS)
} else {
    slideInHorizontally(tween(SHARED_AXIS_POP_DURATION_MS, easing = FastOutSlowInEasing)) { width ->
        -direction.sign * width / SHARED_AXIS_PARALLAX_DIVISOR
    } + fadeIn(tween(SHARED_AXIS_FADE_DURATION_MS, easing = LinearOutSlowInEasing)) togetherWith
        slideOutHorizontally(tween(SHARED_AXIS_POP_DURATION_MS, easing = FastOutSlowInEasing)) { width ->
            direction.sign * width
        } + fadeOut(tween(SHARED_AXIS_FADE_DURATION_MS, easing = LinearEasing))
}

/**
 * Material predictive back for a full-screen page: the page shrinks and shifts toward the side
 * the swipe started from, revealing the previous page underneath; it only fades out over the
 * last part of the gesture. Every spec is linear in time so the preview follows the
 * system-provided (already interpolated) gesture progress exactly.
 */
private fun AnimatedContentTransitionScope<out Scene<*>>.predictivePopTransition(
    @NavigationEvent.SwipeEdge swipeEdge: Int,
    direction: LayoutDirection,
): ContentTransform {
    // Slide offsets are physical pixels, like the swipe edge.
    val edgeSign = when (swipeEdge) {
        NavigationEvent.EDGE_LEFT -> 1
        NavigationEvent.EDGE_RIGHT -> -1
        else -> 0
    }
    val enter = if (targetState.hostsWebView()) {
        // Revealed in place: the WebView surface is never transformed.
        EnterTransition.None
    } else {
        slideInHorizontally(predictiveSpec()) { width ->
            -direction.sign * width / PREDICTIVE_BACK_PARALLAX_DIVISOR
        }
    }
    val exit = scaleOut(predictiveSpec(), targetScale = PREDICTIVE_BACK_TARGET_SCALE) +
        slideOutHorizontally(predictiveSpec()) { width -> edgeSign * width / PREDICTIVE_BACK_SHIFT_DIVISOR } +
        fadeOut(
            tween(
                durationMillis = PREDICTIVE_BACK_FADE_DURATION_MS,
                delayMillis = PREDICTIVE_BACK_DURATION_MS - PREDICTIVE_BACK_FADE_DURATION_MS,
                easing = LinearEasing,
            ),
        )
    return enter togetherWith exit
}

private fun <T> predictiveSpec() = tween<T>(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing)

/** Material fade-through: out quickly, then in, so two full pages never cross-blend. */
private fun fadeThrough(
    outMillis: Int = FADE_OUT_DURATION_MS,
    inMillis: Int = FADE_IN_DURATION_MS,
): ContentTransform =
    fadeIn(
        tween(durationMillis = inMillis, delayMillis = outMillis, easing = LinearOutSlowInEasing),
    ) togetherWith fadeOut(tween(outMillis, easing = LinearEasing))

private val LayoutDirection.sign: Int
    get() = if (this == LayoutDirection.Ltr) 1 else -1

private const val FADE_OUT_DURATION_MS = 90
private const val FADE_IN_DURATION_MS = 180

// Back is quicker than forward: leaving a page should never feel like it waits.
private const val POP_FADE_OUT_DURATION_MS = 75
private const val POP_FADE_IN_DURATION_MS = 150
private const val SHARED_AXIS_DURATION_MS = 300
private const val SHARED_AXIS_POP_DURATION_MS = 200
private const val SHARED_AXIS_FADE_DURATION_MS = 150
private const val SHARED_AXIS_PARALLAX_DIVISOR = 3

/**
 * Length of the gesture-driven transition. Dragging maps progress onto it 1:1; on release only
 * the remaining fraction plays, so a fast swipe ends within ~150 ms of lifting the finger.
 */
private const val PREDICTIVE_BACK_DURATION_MS = 150
private const val PREDICTIVE_BACK_FADE_DURATION_MS = 60
private const val PREDICTIVE_BACK_TARGET_SCALE = 0.9f
private const val PREDICTIVE_BACK_SHIFT_DIVISOR = 20
private const val PREDICTIVE_BACK_PARALLAX_DIVISOR = 10
