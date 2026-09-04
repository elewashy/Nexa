package com.elewashy.nexa.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

/** Material motion pattern used for a navigation hierarchy. */
enum class AppNavigationMotion {
    /** For peer/top-level destinations. Avoids translating expensive surfaces such as WebView. */
    FadeThrough,

    /** For parent/child destinations where horizontal motion communicates hierarchy. */
    SharedAxisX,
}

/**
 * The app-wide navigation host. Transition timing and RTL behavior live here so navigation
 * motion cannot drift between features.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    motion: AppNavigationMotion = AppNavigationMotion.FadeThrough,
    builder: NavGraphBuilder.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { forwardEnter(motion, direction) },
        exitTransition = { forwardExit(motion, direction) },
        popEnterTransition = { backwardEnter(motion, direction) },
        popExitTransition = { backwardExit(motion, direction) },
        builder = builder,
    )
}

private fun forwardEnter(motion: AppNavigationMotion, direction: LayoutDirection): EnterTransition =
    when (motion) {
        AppNavigationMotion.FadeThrough -> fadeIn(
            animationSpec = tween(
                durationMillis = FADE_IN_DURATION_MS,
                delayMillis = FADE_OUT_DURATION_MS,
                easing = EaseOutCubic,
            ),
        )
        AppNavigationMotion.SharedAxisX -> slideInHorizontally(
            animationSpec = tween(SHARED_AXIS_DURATION_MS, easing = EaseOutCubic),
            initialOffsetX = { width -> direction.sign * width },
        ) + fadeIn(animationSpec = tween(SHARED_AXIS_FADE_DURATION_MS))
    }

private fun forwardExit(motion: AppNavigationMotion, direction: LayoutDirection): ExitTransition =
    when (motion) {
        AppNavigationMotion.FadeThrough -> fadeOut(
            animationSpec = tween(FADE_OUT_DURATION_MS, easing = EaseInCubic),
        )
        AppNavigationMotion.SharedAxisX -> slideOutHorizontally(
            animationSpec = tween(SHARED_AXIS_DURATION_MS, easing = EaseInCubic),
            targetOffsetX = { width -> -direction.sign * width / SHARED_AXIS_PARALLAX_DIVISOR },
        ) + fadeOut(animationSpec = tween(SHARED_AXIS_FADE_DURATION_MS))
    }

private fun backwardEnter(motion: AppNavigationMotion, direction: LayoutDirection): EnterTransition =
    when (motion) {
        AppNavigationMotion.FadeThrough -> fadeIn(
            animationSpec = tween(
                durationMillis = FADE_IN_DURATION_MS,
                delayMillis = FADE_OUT_DURATION_MS,
                easing = EaseOutCubic,
            ),
        )
        AppNavigationMotion.SharedAxisX -> slideInHorizontally(
            animationSpec = tween(SHARED_AXIS_DURATION_MS, easing = EaseOutCubic),
            initialOffsetX = { width -> -direction.sign * width / SHARED_AXIS_PARALLAX_DIVISOR },
        ) + fadeIn(animationSpec = tween(SHARED_AXIS_FADE_DURATION_MS))
    }

private fun backwardExit(motion: AppNavigationMotion, direction: LayoutDirection): ExitTransition =
    when (motion) {
        AppNavigationMotion.FadeThrough -> fadeOut(
            animationSpec = tween(FADE_OUT_DURATION_MS, easing = EaseInCubic),
        )
        AppNavigationMotion.SharedAxisX -> slideOutHorizontally(
            animationSpec = tween(SHARED_AXIS_DURATION_MS, easing = EaseInCubic),
            targetOffsetX = { width -> direction.sign * width },
        ) + fadeOut(animationSpec = tween(SHARED_AXIS_FADE_DURATION_MS))
    }

private val LayoutDirection.sign: Int
    get() = if (this == LayoutDirection.Ltr) 1 else -1

private const val FADE_OUT_DURATION_MS = 90
private const val FADE_IN_DURATION_MS = 180
private const val SHARED_AXIS_DURATION_MS = 300
private const val SHARED_AXIS_FADE_DURATION_MS = 150
private const val SHARED_AXIS_PARALLAX_DIVISOR = 3
