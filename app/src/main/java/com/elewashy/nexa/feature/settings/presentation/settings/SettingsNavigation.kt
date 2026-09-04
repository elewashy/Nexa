package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.elewashy.nexa.feature.update.presentation.ChangelogsScreen
import com.elewashy.nexa.feature.update.presentation.UpdatesSettingsScreen
import com.elewashy.nexa.feature.update.presentation.UpdatesSettingsViewModel
import com.elewashy.nexa.ui.navigation.AppNavHost
import com.elewashy.nexa.ui.navigation.AppNavigationMotion

@Composable
fun SettingsNavigation(
    onRootBackClick: () -> Unit,
    onUpdateClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    AppNavHost(
        navController = navController,
        startDestination = SettingsDestination.Root.route,
        motion = AppNavigationMotion.SharedAxisX,
    ) {
        composable(SettingsDestination.Root.route) {
            SettingsScreen(
                onBackClick = onRootBackClick,
                onNavigate = navController::navigateToSettingsDestination,
                viewModel = viewModel,
            )
        }

        composable(SettingsDestination.General.route) {
            GeneralSettingsScreen(
                onBackClick = navController::popBackStack,
                onNavigationPositionClick = {
                    navController.navigateToSettingsDestination(SettingsDestination.BrowserNavigationPosition)
                },
                onCustomizeThemeClick = { navController.navigateToSettingsDestination(SettingsDestination.CustomizeTheme) },
                onLanguageClick = { navController.navigateToSettingsDestination(SettingsDestination.Language) },
                viewModel = viewModel,
            )
        }

        composable(SettingsDestination.BrowserNavigationPosition.route) {
            val position by viewModel.browserNavigationBarPosition.collectAsStateWithLifecycle()
            BrowserNavigationPositionScreen(
                selectedPosition = position,
                onPositionSelected = viewModel::setBrowserNavigationBarPosition,
                onBackClick = navController::popBackStack,
            )
        }

        composable(SettingsDestination.CustomizeTheme.route) {
            CustomizeThemeScreen(
                onBackClick = navController::popBackStack,
                viewModel = viewModel,
            )
        }

        composable(SettingsDestination.Language.route) {
            LanguageSettingsScreen(
                onBackClick = navController::popBackStack,
                viewModel = viewModel,
            )
        }

        composable(SettingsDestination.Updates.route) {
            val updatesViewModel: UpdatesSettingsViewModel = hiltViewModel()
            UpdatesSettingsScreen(
                onBackClick = navController::popBackStack,
                onChangelogClick = { navController.navigateToSettingsDestination(SettingsDestination.Changelog) },
                onUpdateClick = onUpdateClick,
                viewModel = updatesViewModel,
            )
        }

        composable(SettingsDestination.Changelog.route) {
            ChangelogsScreen(
                onBackClick = navController::popBackStack,
            )
        }

        composable(SettingsDestination.About.route) {
            AboutSettingsScreen(
                onBackClick = navController::popBackStack,
                onContributorsClick = { navController.navigateToSettingsDestination(SettingsDestination.Contributors) },
                onLicensesClick = { navController.navigateToSettingsDestination(SettingsDestination.Licenses) },
            )
        }

        composable(SettingsDestination.Contributors.route) {
            ContributorsSettingsScreen(
                onBackClick = navController::popBackStack,
            )
        }

        composable(SettingsDestination.Licenses.route) {
            LicensesSettingsScreen(
                onBackClick = navController::popBackStack,
            )
        }
    }
}

private fun NavHostController.navigateToSettingsDestination(destination: SettingsDestination) {
    navigate(destination.route) {
        launchSingleTop = true
    }
}
