package com.elewashy.nexa.feature.tabs.domain.model

/**
 * Persistent browser tab. Pure data — never holds a WebView or any runtime
 * resource. Runtime tab state (WebView, loading progress) lives in the
 * browser layer and references this identity by [id].
 */
enum class BrowsingMode { Normal, Private }

data class TabItem(
    val id: Long,
    val url: String,
    val title: String,
    val position: Int,
    val isPinned: Boolean = false,
    val isActive: Boolean,
    val createdAt: Long,
    val lastAccessedAt: Long,
    /** Private tabs are process-memory only and never cross the Room boundary. */
    val browsingMode: BrowsingMode = BrowsingMode.Normal,
) {
    val isPrivate: Boolean get() = browsingMode == BrowsingMode.Private
}

/**
 * Immutable snapshot of the complete tab workspace.
 *
 * Publishing tabs and the active pointer together prevents consumers from observing a tab list
 * from one mutation and an active id from another.
 */
data class TabWorkspaceState(
    val tabs: List<TabItem> = emptyList(),
    val activeTabId: Long? = null,
    val isRestored: Boolean = false,
) {
    val activeTab: TabItem? get() = tabs.firstOrNull { it.id == activeTabId }
}
