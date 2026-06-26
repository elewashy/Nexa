package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.elewashy.nexa.feature.update.presentation.ChangelogsScreen
import com.elewashy.nexa.feature.update.presentation.UpdatesSettingsScreen
import com.elewashy.nexa.feature.update.presentation.UpdatesSettingsViewModel

@Composable
fun SettingsNavigation(
    onRootBackClick: () -> Unit,
    onUpdateClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = SettingsDestination.Root.route,
        enterTransition = {
            slideInHorizontally(
                animationSpec = tween(300, easing = EaseOutQuart),
                initialOffsetX = { it },
            )
        },
        exitTransition = {
            slideOutHorizontally(
                animationSpec = tween(300, easing = EaseOutQuart),
                targetOffsetX = { -it / 3 },
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                animationSpec = tween(300, easing = EaseOutQuart),
                initialOffsetX = { -it / 3 },
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = tween(300, easing = EaseOutQuart),
                targetOffsetX = { it },
            )
        },
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
                onCustomizeThemeClick = { navController.navigateToSettingsDestination(SettingsDestination.CustomizeTheme) },
                onLanguageClick = { navController.navigateToSettingsDestination(SettingsDestination.Language) },
                viewModel = viewModel,
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
            )
        }
    }
}

private fun NavHostController.navigateToSettingsDestination(destination: SettingsDestination) {
    navigate(destination.route) {
        launchSingleTop = true
    }
}
