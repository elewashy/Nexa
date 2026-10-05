package com.elewashy.nexa.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.elewashy.nexa.feature.adblock.presentation.AdBlockDestination
import com.elewashy.nexa.feature.downloads.presentation.DownloadsPage
import com.elewashy.nexa.feature.settings.presentation.settings.SettingsDestination
import kotlinx.serialization.Serializable

/**
 * Every destination of the app. The back stack is a plain list of these keys owned by
 * [AppNavigator]; it is serialized into the saved instance state, so the exact page stack
 * survives configuration changes and process death.
 *
 * Keys are sealed and `@Serializable`, so saving needs no reflection and no R8 keep rules, and
 * every key's `toString()` is a stable, unique content key for per-entry state.
 */
@Serializable
sealed interface AppRoute : NavKey {

    /** The browser shell. Always the root of the back stack. */
    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object History : AppRoute

    @Serializable
    data object Bookmarks : AppRoute

    @Serializable
    data object Update : AppRoute

    @Serializable
    data class Downloads(val page: DownloadsPage = DownloadsPage.List) : AppRoute

    @Serializable
    data class AdBlock(val page: AdBlockDestination = AdBlockDestination.Dashboard) : AppRoute

    @Serializable
    data class Settings(val page: SettingsDestination = SettingsDestination.Root) : AppRoute
}
