package com.elewashy.nexa.feature.downloads.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.elewashy.nexa.feature.downloads.presentation.screen.DownloadsRoute
import com.elewashy.nexa.feature.downloads.presentation.settings.DownloadLayoutSettingsRoute
import com.elewashy.nexa.feature.downloads.presentation.settings.DownloadSettingsRoute
import com.elewashy.nexa.ui.navigation.AppNavHost
import com.elewashy.nexa.ui.navigation.AppNavigationMotion

private const val DOWNLOADS_LIST_ROUTE = "list"
private const val DOWNLOADS_SETTINGS_ROUTE = "settings"
private const val DOWNLOADS_DESIGN_ROUTE = "settings/design"

/** Hierarchical Download Manager graph with one centralized, RTL-aware shared-axis transition. */
@Composable
fun DownloadsNavigation(onRootBackClick: () -> Unit) {
    val navController = rememberNavController()
    AppNavHost(
        navController = navController,
        startDestination = DOWNLOADS_LIST_ROUTE,
        motion = AppNavigationMotion.SharedAxisX,
    ) {
        composable(DOWNLOADS_LIST_ROUTE) {
            DownloadsRoute(
                onBackClick = onRootBackClick,
                onSettingsClick = {
                    navController.navigate(DOWNLOADS_SETTINGS_ROUTE) { launchSingleTop = true }
                },
            )
        }
        composable(DOWNLOADS_SETTINGS_ROUTE) {
            DownloadSettingsRoute(
                onBackClick = { navController.popBackStack() },
                onDesignClick = {
                    navController.navigate(DOWNLOADS_DESIGN_ROUTE) { launchSingleTop = true }
                },
            )
        }
        composable(DOWNLOADS_DESIGN_ROUTE) {
            DownloadLayoutSettingsRoute(
                onBackClick = { navController.popBackStack() },
            )
        }
    }
}
