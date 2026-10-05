package com.elewashy.nexa.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.elewashy.nexa.feature.adblock.presentation.AdBlockDestination
import com.elewashy.nexa.feature.downloads.presentation.DownloadsPage
import com.elewashy.nexa.feature.settings.presentation.settings.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppNavigatorTest {

    private fun navigator(vararg routes: AppRoute) =
        AppNavigator(NavBackStack(AppRoute.Browser, *routes))

    @Test
    fun `navigate pushes a new page`() {
        val navigator = navigator()

        navigator.navigate(AppRoute.Settings())

        assertEquals(listOf(AppRoute.Browser, AppRoute.Settings()), navigator.backStack.toList())
        assertEquals(AppRoute.Settings(), navigator.currentRoute)
    }

    @Test
    fun `navigate to the current page does not stack it twice`() {
        val navigator = navigator(AppRoute.History)

        navigator.navigate(AppRoute.History)

        assertEquals(listOf(AppRoute.Browser, AppRoute.History), navigator.backStack.toList())
    }

    @Test
    fun `navigate to a page deeper in the stack returns to it`() {
        val navigator = navigator(
            AppRoute.Downloads(),
            AppRoute.Downloads(DownloadsPage.Settings),
            AppRoute.Downloads(DownloadsPage.Layout),
        )

        navigator.navigate(AppRoute.Downloads())

        assertEquals(listOf(AppRoute.Browser, AppRoute.Downloads()), navigator.backStack.toList())
    }

    @Test
    fun `back pops the top page but never the browser`() {
        val navigator = navigator(AppRoute.Bookmarks)

        navigator.back()
        navigator.back()

        assertEquals(listOf<AppRoute>(AppRoute.Browser), navigator.backStack.toList())
    }

    @Test
    fun `back stack survives saved state round trip`() {
        val stack = NavBackStack(
            AppRoute.Browser,
            AppRoute.Settings(),
            AppRoute.Settings(SettingsDestination.General),
            AppRoute.AdBlock(AdBlockDestination.FilterLists),
            AppRoute.Update,
        )
        val serializer = NavBackStackSerializer(AppRoute.serializer())

        val restored = decodeFromSavedState(serializer, encodeToSavedState(serializer, stack))

        assertEquals(stack.toList(), restored.toList())
    }

    @Test
    fun `every route has a distinct content key`() {
        val routes = listOf(AppRoute.Browser, AppRoute.History, AppRoute.Bookmarks, AppRoute.Update) +
            DownloadsPage.entries.map(AppRoute::Downloads) +
            AdBlockDestination.entries.map(AppRoute::AdBlock) +
            SettingsDestination.entries.map(AppRoute::Settings)

        // Navigation 3 keys saved UI state and ViewModels by the default content key (toString).
        assertEquals(routes.size, routes.map(AppRoute::toString).toSet().size)
    }
}
