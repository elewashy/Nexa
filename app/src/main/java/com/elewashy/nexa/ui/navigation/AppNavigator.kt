package com.elewashy.nexa.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.serialization.NavBackStackSerializer

/**
 * Owner of the app's single back stack. The stack always starts with [AppRoute.Browser] and
 * never contains the same key twice (each key is the identity of its saved UI state and its
 * ViewModels).
 */
@Stable
class AppNavigator internal constructor(val backStack: NavBackStack<AppRoute>) {

    val currentRoute: AppRoute get() = backStack.last()

    /**
     * Shows [route]. Already on top: nothing happens (a double tap never stacks a page twice).
     * Already deeper in the stack (for example a notification opening Downloads while its
     * settings page is open): returns to that page, dropping the pages above it.
     */
    fun navigate(route: AppRoute) {
        val existing = backStack.lastIndexOf(route)
        when {
            existing == backStack.lastIndex -> Unit
            existing >= 0 -> while (backStack.lastIndex > existing) backStack.removeAt(backStack.lastIndex)
            else -> backStack.add(route)
        }
    }

    /** Pops the top page. The root browser page is never popped. */
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}

/** The app's back stack, restored from saved state when available. */
@Composable
fun rememberAppNavigator(): AppNavigator {
    val backStack = rememberSerializable(
        serializer = NavBackStackSerializer(AppRoute.serializer()),
    ) {
        NavBackStack<AppRoute>(AppRoute.Browser)
    }
    return remember(backStack) { AppNavigator(backStack) }
}
