package com.elewashy.nexa.feature.browser.presentation.webview

import com.elewashy.nexa.feature.browser.domain.model.PageLoadError

/**
 * Decides when the app's error page appears over a tab and when it goes away again, so that
 * WebView's own error document underneath is never visible.
 *
 * It appears as soon as [MainFrameErrorTracker] attributes a failure to the main frame. It is
 * *not* dropped as soon as a new document starts: WebView keeps drawing the previous document —
 * its own error page — until the new one paints its first frame, so the error page stays up until
 * that document is visible (`onPageCommitVisible`), or has finished loading for a document that
 * never reports a visible commit. A document restored without `onPageStarted` (back/forward
 * cache) is already rendered and replaces the error page at once.
 *
 * Main thread only, like the WebViewClient callbacks that drive it.
 *
 * @param publish receives the error now shown, or null once a real page replaced it. Clearing is
 *   also published when no error is known here: a tab's error can outlive the WebView client
 *   (a WebView replaced after a renderer crash), and clearing an absent error is a no-op.
 */
internal class ErrorPageVisibility(private val publish: (PageLoadError?) -> Unit) {

    /** URL of the error page currently published. */
    private var shownUrl: String? = null

    /** A real document replaced the error page but has not painted yet. */
    private var awaitingReplacementPaint = false

    /** The main frame now shows [error]'s error page. */
    fun show(error: PageLoadError) {
        awaitingReplacementPaint = false
        shownUrl = error.url
        publish(error)
    }

    /** A document that is not an error page started loading (`onPageStarted`). */
    fun onDocumentStarted() {
        awaitingReplacementPaint = true
    }

    /**
     * The main frame committed [url]. [documentStarted] is whether `onPageStarted` announced
     * that document; without it, a different URL is a page restored fully rendered.
     */
    fun onCommitted(url: String?, documentStarted: Boolean) {
        if (awaitingReplacementPaint || documentStarted) return
        val shown = shownUrl ?: return
        if (url == null || shown.substringBefore('#') != url.substringBefore('#')) clear()
    }

    /** The started document painted (`onPageCommitVisible`) or finished loading. */
    fun onDocumentDisplayed() {
        if (awaitingReplacementPaint) clear()
    }

    private fun clear() {
        awaitingReplacementPaint = false
        shownUrl = null
        publish(null)
    }
}
