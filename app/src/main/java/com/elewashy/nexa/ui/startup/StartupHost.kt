package com.elewashy.nexa.ui.startup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows the start destination of the launcher Activity.
 *
 * - [StartupDestination.Loading] composes nothing: it only lasts while the system splash screen
 *   is held (or, past the splash's hold limit, for the few milliseconds the read still takes),
 *   so the window background shows and no work is spent on a placeholder.
 * - Leaving onboarding fades through to the browser. Every other change is instant, so a
 *   returning user's first frame is the browser itself, with no animation in front of it.
 *
 * Only the current destination is composed (besides the short fade), so the browser — and its
 * WebView — is never created while onboarding is on screen.
 */
@Composable
fun StartupHost(
    destination: StartupDestination,
    onboarding: @Composable () -> Unit,
    browser: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = destination,
        modifier = modifier,
        transitionSpec = { startupTransition() },
        label = "startupDestination",
    ) { target ->
        when (target) {
            StartupDestination.Loading -> Unit
            StartupDestination.Onboarding -> onboarding()
            StartupDestination.Browser -> browser()
        }
    }
}

private fun AnimatedContentTransitionScope<StartupDestination>.startupTransition(): ContentTransform =
    if (initialState == StartupDestination.Onboarding) {
        fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing)) togetherWith
            fadeOut(tween(FADE_OUT_MS, easing = LinearEasing))
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }

private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 210
