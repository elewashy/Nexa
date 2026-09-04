package com.elewashy.nexa.feature.tabs.data

import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import kotlinx.coroutines.flow.StateFlow

/**
 * Public boundary for the complete browser workspace. Room types never cross this interface.
 *
 * [workspace] is the only observable tab state. Every structural mutation publishes the ordered
 * list, active pointer, and restoration status as one immutable snapshot.
 */
interface TabRepository {

    /** Complete ordered workspace. Empty and unrestored until [restore] completes. */
    val workspace: StateFlow<TabWorkspaceState>

    /**
     * Loads the workspace from Room and self-heals: missing/stale active
     * pointer falls back to the most recently used tab; an empty workspace
     * seeds one home tab. Safe to call more than once (first call wins).
     */
    suspend fun restore()

    /**
     * Appends a new tab at [url], makes it active, and returns its id.
     * Returns null when [MAX_TABS] is reached.
     */
    suspend fun newTab(url: String, mode: BrowsingMode = BrowsingMode.Normal): Long?

    /** Switches the active tab. No-op for unknown ids. */
    suspend fun switchTo(tabId: Long)

    /** Pins a tab and moves it to the end of its mode's pinned segment. */
    suspend fun pinTab(tabId: Long)

    /** Unpins a tab and moves it to the start of its mode's unpinned segment. */
    suspend fun unpinTab(tabId: Long)

    /** Applies one pin state to a selection and preserves relative order inside pin segments. */
    suspend fun setTabsPinned(tabIds: Set<Long>, isPinned: Boolean)

    /**
     * Moves a tab to a mode-local canonical [newPosition]. Requests that would
     * cross the pinned/unpinned boundary are no-ops.
     */
    suspend fun reorderTab(tabId: Long, newPosition: Int)

    /**
     * Closes a tab. Closing the active tab activates the next-higher
     * position (else the previous). Closing the last tab creates a fresh
     * home tab in the same transaction — the workspace is never empty.
     */
    suspend fun closeTab(tabId: Long)

    /**
     * Closes a selection as one workspace mutation. Unknown ids are ignored. Closing every normal
     * tab seeds one fresh normal home tab; closing every private tab returns to the normal active tab.
     */
    suspend fun closeTabs(tabIds: Set<Long>)

    /** Closes every tab in [mode]. Normal browsing retains one fresh home tab. */
    suspend fun closeTabs(mode: BrowsingMode)

    /**
     * Application-scoped [closeTabs] for [BrowsingMode.Private]. Safe from `Activity.onDestroy`,
     * where screen-bound coroutine scopes are already cancelled but the private session must end.
     */
    fun discardPrivateTabs()

    /** Coalesced persistence of a committed navigation URL. */
    fun urlCommitted(tabId: Long, url: String)

    /** Coalesced persistence of a page title. */
    fun titleReceived(tabId: Long, title: String)

    /** Queues an application-scoped flush without depending on a screen coroutine's lifetime. */
    fun requestFlush()

    /** Forces all coalesced URL/title writes to disk and awaits completion. */
    suspend fun flushPending()

    companion object {
        /** Hard cap bounds WebView memory growth within a session. */
        const val MAX_TABS = 20
    }
}
