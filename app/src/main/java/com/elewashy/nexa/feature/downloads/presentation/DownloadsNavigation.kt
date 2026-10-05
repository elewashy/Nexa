package com.elewashy.nexa.feature.downloads.presentation

import androidx.navigation3.runtime.EntryProviderScope
import com.elewashy.nexa.feature.downloads.presentation.screen.DownloadsRoute
import com.elewashy.nexa.feature.downloads.presentation.settings.DownloadLayoutSettingsRoute
import com.elewashy.nexa.feature.downloads.presentation.settings.DownloadSettingsRoute
import com.elewashy.nexa.ui.navigation.AppNavigator
import com.elewashy.nexa.ui.navigation.AppRoute

/** Pages of the Download Manager. */
enum class DownloadsPage {
    List,
    Settings,
    Layout,
}

/** The Download Manager's pages: list → settings → layout. */
fun EntryProviderScope<AppRoute>.downloadsEntries(navigator: AppNavigator) {
    entry<AppRoute.Downloads> { route ->
        when (route.page) {
            DownloadsPage.List -> DownloadsRoute(
                onBackClick = navigator::back,
                onSettingsClick = { navigator.navigate(AppRoute.Downloads(DownloadsPage.Settings)) },
            )
            DownloadsPage.Settings -> DownloadSettingsRoute(
                onBackClick = navigator::back,
                onDesignClick = { navigator.navigate(AppRoute.Downloads(DownloadsPage.Layout)) },
            )
            DownloadsPage.Layout -> DownloadLayoutSettingsRoute(onBackClick = navigator::back)
        }
    }
}
