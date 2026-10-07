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
    /** Opens a new tab in the current browsing mode (regular or incognito). */
    val onNewTab: () -> Unit,
    /** Opens a new regular tab, whatever the current mode (tabs button long-press menu). */
    val onNewRegularTab: () -> Unit,
    /** Opens a new incognito tab; null when this WebView cannot isolate incognito browsing. */
    val onNewPrivateTab: (() -> Unit)?,
    val onBookmarks: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onDownloads: () -> Unit,
    val onHistory: () -> Unit,
    val onSettings: () -> Unit,
    /** Opens the Ad blocker page directly. */
    val onAdBlocker: () -> Unit,
    /** Turns ad blocking on or off for the current page's site. */
    val onSetSiteAdBlocking: (Boolean) -> Unit,
)
