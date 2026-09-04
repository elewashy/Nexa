package com.elewashy.nexa.feature.browser.presentation

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.common.BrowserUrls
import com.elewashy.nexa.core.util.SafeUrls.isSafeLoadableUrl
import com.elewashy.nexa.feature.bookmarks.data.BookmarkRepository
import com.elewashy.nexa.feature.browser.data.search.SearchHistoryRepository
import com.elewashy.nexa.feature.browser.data.search.SearchSuggestionRepository
import com.elewashy.nexa.feature.history.data.HistoryRepository
import com.elewashy.nexa.feature.history.domain.model.HistorySuggestion
import com.elewashy.nexa.feature.browser.presentation.webview.NexaWebViewClient
import com.elewashy.nexa.feature.tabs.data.TabRepository
import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel for the browser shell (toolbar, progress bar, tabs, bookmarks).
 *
 * Owns a single [BrowserUiState] StateFlow for the ACTIVE tab's page-level
 * state. Every WebView callback carries its tab id; page-level UI state is
 * updated only for the active tab, while URL/title persistence happens for
 * every tab (a tab keeps loading after the user switches away).
 *
 * The workspace itself (tab list, active pointer) lives in [TabRepository];
 * this VM exposes it to the UI and translates user actions into repository
 * calls. Structural tab events persist immediately there; URL/title updates
 * are coalesced.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val historyRepository: HistoryRepository,
    private val searchSuggestionRepository: SearchSuggestionRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()
    private var renderedActiveTabId: Long? = null

    private val _omniboxState = MutableStateFlow(BrowserOmniboxState())
    val omniboxState: StateFlow<BrowserOmniboxState> = _omniboxState.asStateFlow()
    private val omniboxQueries = MutableStateFlow("")
    private var omniboxLandingJob: Job? = null

    /** Atomic immutable workspace snapshot consumed by the browser UI. */
    val workspace: StateFlow<TabWorkspaceState> = tabRepository.workspace

    /** Bookmark state for every tab URL, synchronized with the shared Room repository. */
    val bookmarkedUrls: StateFlow<Set<String>> = bookmarkRepository.observeBookmarkedUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * One-shot navigation events emitted by [onUrlCommitted]. The Activity
     * loads each URL into the ACTIVE tab's WebView. CONFLATED on purpose:
     * a rapid double-tap collapses into a single navigation.
     */
    private val _navigationEvent = Channel<String>(Channel.CONFLATED)
    val navigationEvent = _navigationEvent.receiveAsFlow()

    /** Bookmark toggle result (true = added) for snackbar feedback. */
    private val _bookmarkToggleEvent = Channel<Boolean>(Channel.CONFLATED)
    val bookmarkToggleEvent = _bookmarkToggleEvent.receiveAsFlow()

    /** Preserves tap order and prevents an older completion overwriting the star. */
    private val bookmarkToggleMutex = Mutex()

    /** Fired when the tab cap blocks a new tab. */
    private val _tabLimitEvent = Channel<Unit>(Channel.CONFLATED)
    val tabLimitEvent = _tabLimitEvent.receiveAsFlow()

    /** Preserves the user's release order when another drag starts before Room acknowledges one. */
    private val tabReorderCommands = Channel<Pair<Long, Int>>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { tabRepository.restore() }
        viewModelScope.launch {
            for ((tabId, targetIndex) in tabReorderCommands) {
                tabRepository.reorderTab(tabId, targetIndex)
            }
        }
        // Retention policy: bounded DELETEs, off-main.
        viewModelScope.launch { historyRepository.prune() }
        viewModelScope.launch {
            tabRepository.workspace.collect { workspace ->
                val activeId = workspace.activeTabId
                val activeTab = workspace.activeTab
                _uiState.update { current ->
                    val activeChanged = renderedActiveTabId != activeId
                    renderedActiveTabId = activeId
                    current.copy(
                        topSearchBarText = if (activeChanged) activeTab?.url.orEmpty()
                            else current.topSearchBarText,
                        pageTitle = if (activeChanged) activeTab?.title.orEmpty() else current.pageTitle,
                        backButtonEnabled = if (activeChanged) false else current.backButtonEnabled,
                        forwardButtonEnabled = if (activeChanged) false else current.forwardButtonEnabled,
                        progress = if (activeChanged) ProgressState.Hidden else current.progress,
                        pageLoadError = if (activeChanged) false else current.pageLoadError,
                        toolbarVisible = if (activeChanged) true else current.toolbarVisible,
                        keepScreenOn = if (activeChanged) false else current.keepScreenOn,
                        isCurrentPageBookmarked = if (activeChanged) false
                            else current.isCurrentPageBookmarked,
                    )
                }
            }
        }
        viewModelScope.launch {
            omniboxQueries
                .debounce(OMNIBOX_DEBOUNCE_MS)
                .distinctUntilChanged()
                .mapLatest { query ->
                    if (query.isBlank()) {
                        OmniboxQueryResults()
                    } else if (isPrivateBrowsing()) {
                        OmniboxQueryResults(
                            remote = searchSuggestionRepository.suggestions(query, OMNIBOX_REMOTE_LIMIT)
                        )
                    } else {
                        val local = async { historyRepository.searchSuggestions(query, OMNIBOX_LOCAL_LIMIT) }
                        val previous = async {
                            searchHistoryRepository.matching(query, OMNIBOX_SEARCH_HISTORY_MATCH_LIMIT)
                        }
                        val remote = async { searchSuggestionRepository.suggestions(query, OMNIBOX_REMOTE_LIMIT) }
                        OmniboxQueryResults(local.await(), previous.await(), remote.await())
                    }
                }
                .collect { results ->
                    _omniboxState.update { state ->
                        if (state.query == omniboxQueries.value) {
                            state.copy(
                                localResults = results.local,
                                matchingSearchHistory = results.previous,
                                remoteResults = results.remote,
                            )
                        } else {
                            state
                        }
                    }
                }
        }
        viewModelScope.launch {
            _uiState
                .map { it.topSearchBarText }
                .distinctUntilChanged()
                .flatMapLatest { url ->
                    if (isSafeLoadableUrl(url)) {
                        bookmarkRepository.observeIsBookmarked(url).map { url to it }
                    } else {
                        flowOf(url to false)
                    }
                }
                .collect { (observedUrl, bookmarked) ->
                    _uiState.update { state ->
                        if (state.topSearchBarText == observedUrl) {
                            state.copy(isCurrentPageBookmarked = bookmarked)
                        } else {
                            state
                        }
                    }
                }
        }
    }

    private val activeTabId: Long?
        get() = tabRepository.workspace.value.activeTabId

    /** Exact history visit created by the latest recordable load in each tab. */
    private val latestVisitByTab = mutableMapOf<Long, Deferred<Long?>>()

    // ── Page lifecycle events (from NexaWebViewClient, tab-scoped) ────

    fun onPageStarted(tabId: Long, url: String?, isImmersiveHost: Boolean) {
        if (tabId != activeTabId) return
        _uiState.update {
            it.copy(
                progress = ProgressState.Loading(0),
                toolbarVisible = !isImmersiveHost,
                topSearchBarText = url ?: "",
                // History affordances intentionally NOT reset here — they
                // keep their previous values until onPageFinished refreshes
                // them, so the buttons don't flicker on every load.
                pageLoadError = false,
                pageLoadId = it.pageLoadId + 1
            )
        }
    }

    fun onProgressChanged(tabId: Long, percent: Int) {
        if (tabId != activeTabId) return
        _uiState.update { state ->
            val updated = state.progress.withWebProgress(percent, PROGRESS_STEP)
            if (updated == state.progress) state else state.copy(progress = updated)
        }
    }

    fun onPageFinished(tabId: Long, canGoBack: Boolean, canGoForward: Boolean) {
        if (tabId != activeTabId) return
        _uiState.update {
            it.copy(
                progress = ProgressState.Hidden,
                backButtonEnabled = canGoBack,
                forwardButtonEnabled = canGoForward
            )
        }
    }

    fun onNavigationConsumed(tabId: Long, canGoBack: Boolean, canGoForward: Boolean) {
        if (tabId != activeTabId) return
        _uiState.update {
            it.copy(
                progress = ProgressState.Hidden,
                backButtonEnabled = canGoBack,
                forwardButtonEnabled = canGoForward
            )
        }
    }

    fun onUrlUpdated(tabId: Long, url: String?) {
        if (tabId != activeTabId) return
        _uiState.update { it.copy(topSearchBarText = url ?: "") }
    }

    fun onPageLoadError(tabId: Long) {
        if (tabId != activeTabId) return
        _uiState.update { it.copy(pageLoadError = true, progress = ProgressState.Hidden) }
    }

    /**
     * Committed main-frame navigation — the persistent URL source of truth.
     * Persists for ANY tab (background tabs keep loading); updates UI and
     * bookmark state only for the active one.
     */
    fun onVisitCommitted(tabId: Long, url: String?, isReload: Boolean) {
        if (url.isNullOrBlank()) return
        tabRepository.urlCommitted(tabId, url)
        if (!isReload && !isPrivateTab(tabId)) {
            latestVisitByTab[tabId] = viewModelScope.async {
                historyRepository.recordVisit(url, isReload = false)
            }
        }
        if (tabId == activeTabId) {
            _uiState.update { it.copy(topSearchBarText = url) }
        }
    }

    /** Page title — persisted for any tab, shown only for the active one. */
    fun onReceivedTitle(tabId: Long, url: String?, title: String?) {
        if (url.isNullOrBlank() || title.isNullOrBlank()) return
        tabRepository.titleReceived(tabId, title)
        val visit = latestVisitByTab[tabId].takeUnless { isPrivateTab(tabId) }
        if (visit != null) {
            viewModelScope.launch { visit.await()?.let { historyRepository.updateTitle(it, title) } }
        }
        if (tabId == activeTabId) {
            _uiState.update { it.copy(pageTitle = title) }
        }
    }

    /**
     * The Activity calls this after attaching a newly active tab's WebView,
     * re-syncing all page-level UI state from that WebView's live values.
     */
    fun onActiveTabAttached(
        url: String?,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        _uiState.update {
            it.copy(
                topSearchBarText = url ?: "",
                pageTitle = title.orEmpty(),
                backButtonEnabled = canGoBack,
                forwardButtonEnabled = canGoForward,
                progress = ProgressState.Hidden,
                pageLoadError = false,
                toolbarVisible = !NexaWebViewClient.isImmersiveUrl(url),
                pageLoadId = it.pageLoadId + 1,
            )
        }
    }

    // ── Fullscreen events (from NexaWebChromeClient) ─────────────────

    fun onFullscreenEnter(tabId: Long) {
        if (tabId != activeTabId) return
        _uiState.update { it.copy(toolbarVisible = false, keepScreenOn = true) }
    }

    fun onFullscreenExit(tabId: Long) {
        if (tabId != activeTabId) {
            // Exit forced by a tab switch: the shared container is gone, so
            // the screen-on flag must clear even though UI state (toolbar)
            // now belongs to the new tab.
            _uiState.update { it.copy(keepScreenOn = false) }
            return
        }
        _uiState.update {
            it.copy(
                toolbarVisible = !NexaWebViewClient.isImmersiveUrl(it.topSearchBarText),
                keepScreenOn = false
            )
        }
    }

    // ── Tab operations ───────────────────────────────────────────────

    fun newTab() = createTab(BrowsingMode.Normal)

    fun newPrivateTab() = createTab(BrowsingMode.Private)

    private fun createTab(mode: BrowsingMode) = viewModelScope.launch {
        val id = tabRepository.newTab(BrowserUrls.HOME, mode)
        if (id == null) _tabLimitEvent.trySend(Unit)
    }

    fun reopenTab(tab: TabItem) {
        viewModelScope.launch {
            val id = tabRepository.newTab(tab.url, tab.browsingMode)
            if (id == null) {
                _tabLimitEvent.trySend(Unit)
            } else {
                if (tab.title.isNotBlank()) tabRepository.titleReceived(id, tab.title)
                if (tab.isPinned) tabRepository.pinTab(id)
                tabRepository.reorderTab(id, tab.position)
            }
        }
    }

    fun setTabPinned(tabId: Long, pinned: Boolean) {
        viewModelScope.launch {
            if (pinned) tabRepository.pinTab(tabId) else tabRepository.unpinTab(tabId)
        }
    }

    fun setTabsPinned(tabIds: Set<Long>, pinned: Boolean) {
        viewModelScope.launch { tabRepository.setTabsPinned(tabIds, pinned) }
    }

    fun reorderTab(tabId: Long, newPosition: Int) {
        tabReorderCommands.trySend(tabId to newPosition)
    }

    fun switchTab(tabId: Long) = viewModelScope.launch {
        tabRepository.switchTo(tabId)
    }

    fun closeTab(tabId: Long) {
        latestVisitByTab.remove(tabId)
        viewModelScope.launch { tabRepository.closeTab(tabId) }
    }

    fun closeTabs(tabIds: Set<Long>) {
        latestVisitByTab.keys.removeAll(tabIds)
        viewModelScope.launch { tabRepository.closeTabs(tabIds) }
    }

    fun closeTabs(mode: BrowsingMode) {
        val closingPrivate = mode == BrowsingMode.Private
        latestVisitByTab.keys.removeAll { isPrivateTab(it) == closingPrivate }
        if (closingPrivate) {
            // Also invoked from Activity.onDestroy, after this scope may already be cancelled.
            tabRepository.discardPrivateTabs()
        } else {
            viewModelScope.launch { tabRepository.closeTabs(mode) }
        }
    }

    /** Forces coalesced URL/title writes to disk (Activity onStop) without depending on this scope. */
    fun flushTabs() = tabRepository.requestFlush()

    // ── Bookmarks ────────────────────────────────────────────────────

    fun toggleBookmark() {
        toggleBookmark(
            url = _uiState.value.topSearchBarText,
            title = _uiState.value.pageTitle,
        )
    }

    fun toggleBookmark(url: String, title: String) {
        if (!isSafeLoadableUrl(url)) return
        viewModelScope.launch {
            bookmarkToggleMutex.withLock {
                val added = bookmarkRepository.toggle(url, title)
                _bookmarkToggleEvent.trySend(added)
                _uiState.update {
                    // The user may navigate while Room is completing the toggle;
                    // never apply the old page's result to the new page's star.
                    if (it.topSearchBarText == url) {
                        it.copy(isCurrentPageBookmarked = added)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun setTabsBookmarked(tabs: List<TabItem>, bookmarked: Boolean) {
        val bookmarkableTabs = tabs.filter { isSafeLoadableUrl(it.url) }
        if (bookmarkableTabs.isEmpty()) return
        viewModelScope.launch {
            bookmarkToggleMutex.withLock {
                bookmarkableTabs.forEach { tab ->
                    val currentlyBookmarked = bookmarkRepository.byUrl(tab.url) != null
                    if (currentlyBookmarked != bookmarked) {
                        bookmarkRepository.toggle(tab.url, tab.title)
                    }
                }
                _bookmarkToggleEvent.trySend(bookmarked)
            }
        }
    }

    // ── URL bar navigation ──────────────────────────────────────────

    fun onUrlCommitted(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return

        val directUrl = when {
            isSafeLoadableUrl(trimmed) -> trimmed
            // host:port(/path) is a URL, not a scheme-like search term —
            // must run before the generic ':' check below.
            HOST_WITH_PORT_PATTERN.matches(trimmed) -> "https://$trimmed"
            trimmed.contains('.') && !trimmed.contains(' ') && !trimmed.contains(':') -> "https://$trimmed"
            else -> null
        }
        // Scheme-like input (javascript:, data:, file:, …) must never be loaded or blindly
        // upgraded to HTTPS. It remains a search query and is recorded only in query history.
        val searchQuery = trimmed.takeIf { directUrl == null }
        val url = directUrl ?: "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        if (searchQuery != null && !isPrivateBrowsing()) {
            viewModelScope.launch { searchHistoryRepository.record(searchQuery) }
        }

        _uiState.update { it.copy(topSearchBarText = url) }
        _omniboxState.value = BrowserOmniboxState()
        omniboxQueries.value = ""
        _navigationEvent.trySend(url)
    }

    // ── Omnibox interaction ───────────────────────────────────────

    fun toggleAddressPreview() {
        _omniboxState.update { state ->
            BrowserOmniboxState(
                mode = if (state.mode == BrowserOmniboxMode.Preview) {
                    BrowserOmniboxMode.Collapsed
                } else {
                    BrowserOmniboxMode.Preview
                }
            )
        }
    }

    fun openOmniboxSearch() = openOmnibox(BrowserOmniboxMode.Search, "")

    fun openOmniboxUrlEditor() = openOmnibox(
        mode = BrowserOmniboxMode.EditUrl,
        initialQuery = _uiState.value.topSearchBarText,
    )

    fun updateOmniboxQuery(query: String) {
        val bounded = query.take(MAX_OMNIBOX_QUERY_LENGTH)
        _omniboxState.update {
            it.copy(
                query = bounded,
                localResults = if (bounded.isBlank()) emptyList() else it.localResults,
                matchingSearchHistory = if (bounded.isBlank()) emptyList() else it.matchingSearchHistory,
                remoteResults = if (bounded.isBlank()) emptyList() else it.remoteResults,
            )
        }
        omniboxQueries.value = bounded
    }

    fun dismissOmnibox() {
        omniboxLandingJob?.cancel()
        omniboxLandingJob = null
        _omniboxState.value = BrowserOmniboxState()
        omniboxQueries.value = ""
    }

    private fun openOmnibox(mode: BrowserOmniboxMode, initialQuery: String) {
        omniboxLandingJob?.cancel()
        omniboxLandingJob = null
        _omniboxState.value = BrowserOmniboxState(mode = mode, query = initialQuery)
        omniboxQueries.value = initialQuery
        if (initialQuery.isNotBlank() || isPrivateBrowsing()) return
        omniboxLandingJob = viewModelScope.launch {
            val frequentRequest = async {
                historyRepository.frequentSuggestions(OMNIBOX_FREQUENT_QUERY_LIMIT)
            }
            val searchHistoryRequest = async {
                searchHistoryRepository.recent(OMNIBOX_SEARCH_HISTORY_LIMIT)
            }
            val frequentSites = frequentRequest.await()
                .distinctBy { suggestion ->
                    runCatching { suggestion.url.toUri().host?.lowercase() }
                        .getOrNull() ?: suggestion.url.lowercase()
                }
                .take(OMNIBOX_FREQUENT_LIMIT)
            val searchHistory = searchHistoryRequest.await()
            _omniboxState.update { state ->
                if (state.mode.isOverlayVisible) {
                    state.copy(frequentSites = frequentSites, searchHistory = searchHistory)
                } else {
                    state
                }
            }
        }
    }

    private fun isPrivateBrowsing(): Boolean =
        tabRepository.workspace.value.activeTab?.isPrivate == true

    private fun isPrivateTab(tabId: Long): Boolean = tabId < 0L ||
        tabRepository.workspace.value.tabs.firstOrNull { it.id == tabId }?.isPrivate == true

    private data class OmniboxQueryResults(
        val local: List<HistorySuggestion> = emptyList(),
        val previous: List<String> = emptyList(),
        val remote: List<String> = emptyList(),
    )

    private companion object {
        /** Minimum progress increment worth re-rendering the bar. */
        private const val PROGRESS_STEP = 5
        private const val OMNIBOX_DEBOUNCE_MS = 220L
        private const val OMNIBOX_LOCAL_LIMIT = 6
        private const val OMNIBOX_REMOTE_LIMIT = 8
        private const val OMNIBOX_FREQUENT_LIMIT = 8
        private const val OMNIBOX_FREQUENT_QUERY_LIMIT = 24
        private const val OMNIBOX_SEARCH_HISTORY_LIMIT = 8
        private const val OMNIBOX_SEARCH_HISTORY_MATCH_LIMIT = 6
        private const val MAX_OMNIBOX_QUERY_LENGTH = 2048

        /**
         * host:port(/path) with a numeric port. Requires a dotted host so
         * time-like inputs such as "12:30" stay searches.
         */
        private val HOST_WITH_PORT_PATTERN = Regex(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+:\\d{1,5}(/\\S*)?$",
            RegexOption.IGNORE_CASE
        )
    }
}
