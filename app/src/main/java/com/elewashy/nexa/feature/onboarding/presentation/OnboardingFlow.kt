package com.elewashy.nexa.feature.onboarding.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import com.elewashy.nexa.feature.settings.presentation.settings.BrowserNavigationPositionRoute
import com.elewashy.nexa.feature.settings.presentation.settings.CustomizeThemeScreen
import com.elewashy.nexa.ui.navigation.NexaNavDisplay
import kotlinx.serialization.Serializable

/** Pages of the onboarding flow. Serializable: the flow's back stack survives process death. */
@Serializable
sealed interface OnboardingDestination : NavKey {

    /** The onboarding steps. Always the root. */
    @Serializable
    data object Steps : OnboardingDestination

    /** The app's Theme page, as in Settings → General. */
    @Serializable
    data object Theme : OnboardingDestination

    /** The app's navigation-bar position page, as in Settings → General. */
    @Serializable
    data object NavigationBarPosition : OnboardingDestination
}

/**
 * The first-launch onboarding. A small Navigation 3 back stack of its own (it is shown before
 * the browser exists, outside the app's main stack), on the same host as the rest of the app:
 * every page keeps its saved state and owns its ViewModels, and Back — including predictive
 * back — returns from the Theme and navigation-bar pages to the step the user was on.
 */
@Composable
fun OnboardingFlow(modifier: Modifier = Modifier) {
    val backStack = rememberSerializable(
        serializer = NavBackStackSerializer(OnboardingDestination.serializer()),
    ) {
        NavBackStack<OnboardingDestination>(OnboardingDestination.Steps)
    }
    val navigator = remember(backStack) { OnboardingNavigator(backStack) }

    NexaNavDisplay(
        backStack = backStack,
        onBack = navigator::back,
        modifier = modifier,
    ) {
        entry<OnboardingDestination.Steps> {
            OnboardingStepsRoute(
                onOpenTheme = { navigator.open(OnboardingDestination.Theme) },
                onOpenNavigationBarPosition = { navigator.open(OnboardingDestination.NavigationBarPosition) },
            )
        }
        // The real pages, with their own entry-scoped ViewModels; choices apply immediately.
        entry<OnboardingDestination.Theme> {
            CustomizeThemeScreen(onBackClick = navigator::back, viewModel = hiltViewModel())
        }
        entry<OnboardingDestination.NavigationBarPosition> {
            BrowserNavigationPositionRoute(onBackClick = navigator::back)
        }
    }
}

/** Opens at most one page above the steps (a double tap never stacks it twice). */
private class OnboardingNavigator(private val backStack: NavBackStack<OnboardingDestination>) {

    fun open(destination: OnboardingDestination) {
        if (backStack.last() != destination) backStack.add(destination)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
