package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import com.elewashy.nexa.feature.adblock.presentation.AdBlockDestination
import com.elewashy.nexa.feature.update.presentation.ChangelogsScreen
import com.elewashy.nexa.feature.update.presentation.UpdatesSettingsScreen
import com.elewashy.nexa.ui.navigation.AppNavigator
import com.elewashy.nexa.ui.navigation.AppRoute

/**
 * The Settings pages. Every page is its own back-stack entry, so each one owns its saved UI
 * state and its ViewModels (cleared when the page is popped).
 */
fun EntryProviderScope<AppRoute>.settingsEntries(
    navigator: AppNavigator,
    onUpdateClick: () -> Unit,
) {
    entry<AppRoute.Settings> { route ->
        SettingsPage(
            page = route.page,
            navigator = navigator,
            onUpdateClick = onUpdateClick,
        )
    }
}

@Composable
private fun SettingsPage(
    page: SettingsDestination,
    navigator: AppNavigator,
    onUpdateClick: () -> Unit,
) {
    val open: (SettingsDestination) -> Unit = { destination ->
        navigator.navigate(AppRoute.Settings(destination))
    }
    val back: () -> Unit = navigator::back

    when (page) {
        SettingsDestination.Root -> SettingsScreen(
            onBackClick = back,
            onNavigate = open,
            // The ad blocker is a feature of its own, shared with the browser menu.
            onAdBlockClick = { navigator.navigate(AppRoute.AdBlock(AdBlockDestination.Dashboard)) },
        )

        SettingsDestination.General -> GeneralSettingsScreen(
            onBackClick = back,
            onNavigationPositionClick = { open(SettingsDestination.BrowserNavigationPosition) },
            onCustomizeThemeClick = { open(SettingsDestination.CustomizeTheme) },
            onLanguageClick = { open(SettingsDestination.Language) },
            onSearchEngineClick = { open(SettingsDestination.SearchEngine) },
            viewModel = hiltViewModel(),
        )

        SettingsDestination.BrowserNavigationPosition -> BrowserNavigationPositionRoute(onBackClick = back)

        SettingsDestination.CustomizeTheme -> CustomizeThemeScreen(
            onBackClick = back,
            viewModel = hiltViewModel(),
        )

        SettingsDestination.Language -> LanguageSettingsScreen(
            onBackClick = back,
            viewModel = hiltViewModel(),
        )

        SettingsDestination.SearchEngine -> SearchEngineSettingsScreen(
            onBackClick = back,
            viewModel = hiltViewModel(),
        )

        SettingsDestination.Updates -> UpdatesSettingsScreen(
            onBackClick = back,
            onChangelogClick = { open(SettingsDestination.Changelog) },
            onUpdateClick = onUpdateClick,
            viewModel = hiltViewModel(),
        )

        SettingsDestination.Changelog -> ChangelogsScreen(onBackClick = back)

        SettingsDestination.About -> AboutScreen(
            onBackClick = back,
            onContributorsClick = { open(SettingsDestination.Contributors) },
            onLicensesClick = { open(SettingsDestination.Licenses) },
        )

        SettingsDestination.Contributors -> ContributorsSettingsScreen(onBackClick = back)

        SettingsDestination.Licenses -> LicensesSettingsScreen(onBackClick = back)
    }
}
