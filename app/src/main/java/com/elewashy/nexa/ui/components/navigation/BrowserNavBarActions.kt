package com.elewashy.nexa.ui.components.navigation

import androidx.compose.runtime.Immutable

/**
 * Every navigation action the browser chrome can trigger.
 *
 * The compact bottom bar, the compact top bar, and the large-window side rail
 * expose the same set of actions; bundling them keeps the three surfaces on a
 * single contract and lets the host pass one stable reference instead of
 * re-threading a dozen lambdas through every layer.
 */
@Immutable
class BrowserNavBarActions(
    val onRefresh: () -> Unit,
    /** Opens the full omnibox for search / URL entry. */
    val onOpenSearch: () -> Unit,
    val onHome: () -> Unit,
    val onTabs: () -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onShare: (String) -> Unit,
    val onNewTab: () -> Unit,
    val onBookmarks: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onDownloads: () -> Unit,
    val onHistory: () -> Unit,
    val onSettings: () -> Unit,
)
