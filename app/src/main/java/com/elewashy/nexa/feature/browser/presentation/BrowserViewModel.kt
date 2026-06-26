package com.elewashy.nexa.feature.browser.presentation

import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
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
     * the WebView's `loadUrl()`. Using a [Channel] (capacity = CONFLATED)
     * ensures no emission is lost even if the observer is momentarily
     * unavailable, and that a rapid double-tap only triggers one load.
     */
    private val _navigationEvent = Channel<String>(Channel.CONFLATED)
    val navigationEvent = _navigationEvent.receiveAsFlow()

    // ── Page lifecycle events (from NexaWebViewClient) ───────────────

    fun onPageStarted(url: String?, isImmersiveHost: Boolean) {
        _uiState.update {
            it.copy(
                progress = ProgressState.Loading(0),
                toolbarVisible = !isImmersiveHost,
                topSearchBarText = url ?: ""
            )
        }
    }

    /** `onProgressChanged` from either client; updates the bar value. */
    fun onProgressChanged(percent: Int) {
        _uiState.update {
            val current = it.progress
            when {
                percent >= 100 -> it.copy(progress = ProgressState.Hidden)
                current is ProgressState.Loading && current.percent == percent -> it
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

    // ── Fullscreen events (from NexaWebChromeClient) ─────────────────

    /** Fullscreen custom view shown: hide toolbar, keep screen on. */
    fun onFullscreenEnter() {
        _uiState.update { it.copy(toolbarVisible = false, keepScreenOn = true) }
    }

    /** Fullscreen custom view hidden: show toolbar, release screen. */
    fun onFullscreenExit() {
        _uiState.update { it.copy(toolbarVisible = true, keepScreenOn = false) }
    }

    // ── URL bar navigation ──────────────────────────────────────────

    fun onUrlCommitted(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return

        val url = when {
            URLUtil.isValidUrl(trimmed) -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }

        _uiState.update { it.copy(topSearchBarText = url, urlBarVisible = false) }
        _navigationEvent.trySend(url)
    }

    // ── Toolbar toggles ────────────────────────────────────────────

    fun toggleUrlContainer() {
        _uiState.update { it.copy(urlBarVisible = !it.urlBarVisible) }
    }
}
