package com.elewashy.nexa.feature.adblock.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.elewashy.nexa.ui.navigation.AppNavHost
import com.elewashy.nexa.ui.navigation.AppNavigationMotion

/** Pages of the ad blocker; the dashboard is the entry point. */
enum class AdBlockDestination(val route: String) {
    Dashboard("adblock"),
    FilterLists("adblock/filter-lists"),
    CustomRules("adblock/custom-rules"),
    Sites("adblock/sites"),
    Statistics("adblock/statistics"),
}

/**
 * The ad blocker's own navigation graph. Hosted both by the browser menu
 * (direct access) and by Settings, so both entry points show the same
 * pages with the same back behaviour.
 */
@Composable
fun AdBlockNavigation(
    onRootBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    AppNavHost(
        navController = navController,
        startDestination = AdBlockDestination.Dashboard.route,
        motion = AppNavigationMotion.SharedAxisX,
        modifier = modifier,
    ) {
        composable(AdBlockDestination.Dashboard.route) {
            AdBlockDashboardScreen(
                onBackClick = onRootBackClick,
                onNavigate = navController::navigateTo,
            )
        }
        composable(AdBlockDestination.FilterLists.route) {
            FilterListsScreen(onBackClick = navController::popBackStack)
        }
        composable(AdBlockDestination.CustomRules.route) {
            CustomRulesScreen(onBackClick = navController::popBackStack)
        }
        composable(AdBlockDestination.Sites.route) {
            SitesScreen(onBackClick = navController::popBackStack)
        }
        composable(AdBlockDestination.Statistics.route) {
            AdBlockStatisticsScreen(onBackClick = navController::popBackStack)
        }
    }
}

private fun NavHostController.navigateTo(destination: AdBlockDestination) {
    navigate(destination.route) { launchSingleTop = true }
}
