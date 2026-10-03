package com.elewashy.nexa.feature.tabs.data

import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import kotlinx.coroutines.flow.StateFlow

/**
 * Public boundary for the complete browser workspace. Room types never cross this interface.
 *
 * [workspace] is the only observable tab state. Every structural mutation publishes the ordered
 * list, active pointer, and restoration status as one immutable snapshot.
 *
 * Each tab additionally owns an opaque navigation-state blob ([saveSessionState] /
 * [sessionState]) produced by the browser layer, so a restored tab keeps its complete
 * back/forward history. Normal tabs persist it in Room; private tabs keep it in process memory.
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
     *
     * [openerTabId] records the tab whose page opened this one; it is ignored unless it names a
     * live tab in the same [mode].
     */
    suspend fun newTab(
        url: String,
        mode: BrowsingMode = BrowsingMode.Normal,
        openerTabId: Long? = null,
    ): Long?

    /**
     * Re-creates a just-closed [tab] (undo) atomically: URL, title, pin state, canonical position,
     * live opener, and — when still retained — its navigation history. Returns the new id, or
     * null when [MAX_TABS] is reached.
     */
    suspend fun reopenClosedTab(tab: TabItem): Long?

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
     * Closes a tab. Closing the active tab activates its nearest live opener, else the
     * next-higher position (else the previous). Children of a closed tab are re-parented to its
     * nearest surviving ancestor. Closing the last tab creates a fresh home tab in the same
     * transaction — the workspace is never empty.
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

    /**
     * Records the latest opaque navigation state of a live tab. Coalesced like URL/title writes;
     * readable immediately through [sessionState]. A state larger than [MAX_SESSION_STATE_BYTES]
     * clears the stored one instead, so a restore never resurrects an outdated history.
     */
    fun saveSessionState(tabId: Long, state: ByteArray)

    /** The latest navigation state recorded for [tabId], or null when none is available. */
    suspend fun sessionState(tabId: Long): ByteArray?

    /** Queues an application-scoped flush without depending on a screen coroutine's lifetime. */
    fun requestFlush()

    /** Forces all coalesced URL/title writes to disk and awaits completion. */
    suspend fun flushPending()

    companion object {
        /** Hard cap bounds WebView memory growth within a session. */
        const val MAX_TABS = 20

        /**
         * Upper bound for one tab's navigation state. Well below the ~2 MB SQLite CursorWindow
         * row limit so a stored state can always be read back.
         */
        const val MAX_SESSION_STATE_BYTES = 1024 * 1024
    }
}
