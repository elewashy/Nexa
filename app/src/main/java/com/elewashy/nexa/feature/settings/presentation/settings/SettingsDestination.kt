package com.elewashy.nexa.feature.settings.presentation.settings

sealed interface SettingsDestination {
    val route: String

    data object Root : SettingsDestination {
        override val route = "settings"
    }

    data object General : SettingsDestination {
        override val route = "settings/general"
    }

    data object CustomizeTheme : SettingsDestination {
        override val route = "settings/general/customize-theme"
    }

    data object Language : SettingsDestination {
        override val route = "settings/general/language"
    }

    data object Updates : SettingsDestination {
        override val route = "settings/updates"
    }

    data object Changelog : SettingsDestination {
        override val route = "settings/changelog"
    }

    data object About : SettingsDestination {
        override val route = "settings/about"
    }
}
