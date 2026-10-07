package com.elewashy.nexa.feature.settings.presentation.settings

/**
 * Pages of Settings. Carried by [com.elewashy.nexa.ui.navigation.AppRoute.Settings], so it is
 * part of the saved back stack and must stay serializable (an enum is, by default).
 */
enum class SettingsDestination {
    Root,
    General,
    BrowserNavigationPosition,
    CustomizeTheme,
    Language,
    SearchEngine,
    HomePage,
    Updates,
    Changelog,
    About,
    Contributors,
    Licenses,
}
