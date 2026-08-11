package com.elewashy.nexa.feature.browser.presentation

import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import com.elewashy.nexa.feature.browser.presentation.webview.NexaWebViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel for the browser shell (toolbar, progress bar, keep-screen-on).
 *
 * Owns a single [BrowserUiState] StateFlow. `BrowserActivity` observes it via
 * `collectAsStateWithLifecycle()` and renders the Compose UI accordingly.
 * WebView clients dispatch high-level events here; they never touch
 * Activity-mutator methods directly.
 *
 * Scope: activity-scoped (`by viewModels()`).
 * Activity scope is required because `NexaWebViewClient` and
 * `NexaWebChromeClient` outlive any single page load but stay inside one
 * Activity instance.
 *
 * Not scope of this VM (stays on the Activity as plain methods):
 *  - WebView history navigation (`goBack` / `goForward`) — operates on the
 *    actual WebView instance held by the Activity.
 *  - `requestedOrientation` changes from fullscreen — a direct Activity API.
 *  - `window.addFlags(FLAG_KEEP_SCREEN_ON)` — lives in the Activity's
 *    lifecycle observer, driven by `keepScreenOn` in state.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /**
     * One-shot navigation events emitted by [onUrlCommitted].
     * The Activity consumes each emission exactly once and calls
     * the WebView's `loadUrl()`. The [Channel] is CONFLATED on
     * purpose — last-wins: if several commits arrive before the
     * observer drains them, only the most recent URL is loaded, so
     * a rapid double-tap collapses into a single navigation.
     */
    private val _navigationEvent = Channel<String>(Channel.CONFLATED)
    val navigationEvent = _navigationEvent.receiveAsFlow()

    private companion object {
        /** Minimum progress increment worth re-rendering the bar. */
        private const val PROGRESS_STEP = 5

        /**
         * host:port(/path) with a numeric port. Requires a dotted host so
         * time-like inputs such as "12:30" stay searches.
         */
        private val HOST_WITH_PORT_PATTERN = Regex(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+:\\d{1,5}(/\\S*)?$",
            RegexOption.IGNORE_CASE
        )
    }

    // ── Page lifecycle events (from NexaWebViewClient) ───────────────

    fun onPageStarted(url: String?, isImmersiveHost: Boolean) {
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

    /** `onProgressChanged` from either client; updates the bar value. */
    fun onProgressChanged(percent: Int) {
        _uiState.update {
            val current = it.progress
            when {
                percent >= 100 -> it.copy(progress = ProgressState.Hidden)
                // Coarsen to ~5% steps to reduce state churn. Backward jumps
                // (a new navigation starting) always pass through.
                current is ProgressState.Loading &&
                    percent >= current.percent &&
                    percent - current.percent < PROGRESS_STEP -> it
                else -> it.copy(progress = ProgressState.Loading(percent))
            }
        }
    }

    /** `onPageFinished` hides the progress bar and refreshes history affordances. */
    fun onPageFinished(canGoBack: Boolean, canGoForward: Boolean = false) {
        _uiState.update {
            it.copy(
                progress = ProgressState.Hidden,
                backButtonEnabled = canGoBack,
                forwardButtonEnabled = canGoForward
            )
        }
    }

    /** Navigation was consumed by WebViewClient (blocked, downloaded, or external). */
    fun onNavigationConsumed(canGoBack: Boolean, canGoForward: Boolean = false) {
        _uiState.update {
            it.copy(
                progress = ProgressState.Hidden,
                backButtonEnabled = canGoBack,
                forwardButtonEnabled = canGoForward
            )
        }
    }

    /** `doUpdateVisitedHistory` — update the debug URL text. */
    fun onUrlUpdated(url: String?) {
        _uiState.update { it.copy(topSearchBarText = url ?: "") }
    }

    /** Main-frame load failed (WebViewClient error event). */
    fun onPageLoadError() {
        _uiState.update { it.copy(pageLoadError = true) }
    }

    // ── Fullscreen events (from NexaWebChromeClient) ─────────────────

    /** Fullscreen custom view shown: hide toolbar, keep screen on. */
    fun onFullscreenEnter() {
        _uiState.update { it.copy(toolbarVisible = false, keepScreenOn = true) }
    }

    /** Fullscreen custom view hidden: release the screen and restore the
     *  toolbar — unless the current page is an immersive host, which keeps
     *  it hidden. */
    fun onFullscreenExit() {
        _uiState.update {
            it.copy(
                toolbarVisible = !NexaWebViewClient.isImmersiveUrl(it.topSearchBarText),
                keepScreenOn = false
            )
        }
    }

    // ── URL bar navigation ──────────────────────────────────────────

    fun onUrlCommitted(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return

        val url = when {
            isSafeLoadableUrl(trimmed) -> trimmed
            // host:port(/path) is a URL, not a scheme-like search term —
            // must run before the generic ':' check below.
            HOST_WITH_PORT_PATTERN.matches(trimmed) -> "https://$trimmed"
            // Scheme-like input (javascript:, data:, file:, …) must never be
            // loaded or blindly upgraded to https — search for it instead.
            trimmed.contains(':') -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }

        _uiState.update { it.copy(topSearchBarText = url, urlBarVisible = false) }
        _navigationEvent.trySend(url)
    }

    /**
     * Only plain http(s) URLs may be loaded. [URLUtil.isValidUrl] also
     * accepts javascript:, data: and file: URLs, which would let the URL
     * bar execute script or read local files.
     */
    private fun isSafeLoadableUrl(input: String): Boolean {
        if (!URLUtil.isValidUrl(input)) return false
        val scheme = Uri.parse(input).scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    // ── Toolbar toggles ────────────────────────────────────────────

    fun toggleUrlContainer() {
        _uiState.update { it.copy(urlBarVisible = !it.urlBarVisible) }
    }
}
