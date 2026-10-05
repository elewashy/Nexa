package com.elewashy.nexa.feature.adblock.presentation

import androidx.navigation3.runtime.EntryProviderScope
import com.elewashy.nexa.ui.navigation.AppNavigator
import com.elewashy.nexa.ui.navigation.AppRoute

/** Pages of the ad blocker; the dashboard is the entry point. */
enum class AdBlockDestination {
    Dashboard,
    FilterLists,
    CustomRules,
    Sites,
    Statistics,
}

/**
 * The ad blocker's pages. Opened both from the browser menu and from Settings; both entry points
 * share the same pages and back behaviour because they live in the app's single back stack.
 */
fun EntryProviderScope<AppRoute>.adBlockEntries(navigator: AppNavigator) {
    entry<AppRoute.AdBlock> { route ->
        when (route.page) {
            AdBlockDestination.Dashboard -> AdBlockDashboardScreen(
                onBackClick = navigator::back,
                onNavigate = { page -> navigator.navigate(AppRoute.AdBlock(page)) },
            )
            AdBlockDestination.FilterLists -> FilterListsScreen(onBackClick = navigator::back)
            AdBlockDestination.CustomRules -> CustomRulesScreen(onBackClick = navigator::back)
            AdBlockDestination.Sites -> SitesScreen(onBackClick = navigator::back)
            AdBlockDestination.Statistics -> AdBlockStatisticsScreen(onBackClick = navigator::back)
        }
    }
}
