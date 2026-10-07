package com.elewashy.nexa.feature.browser.presentation.webview

import com.elewashy.nexa.feature.browser.domain.model.PageLoadError

/**
 * Decides which main-frame errors really left the tab on an error page.
 *
 * `onReceivedError(isForMainFrame)` and `onReceivedSslError` are reported for the *request*, not
 * for what ends up on screen. Several of those requests never replace the visible page:
 *  - speculative main-frame requests (speculation-rules prefetch) for links the user has not
 *    opened yet,
 *  - subresources served from the page's own host, whose certificate errors arrive through the
 *    same SSL callback as the document's,
 *  - errors that arrive after the navigation they belong to was superseded.
 *
 * Treating every one of those as a page failure put the "can't be loaded" screen over pages that
 * had opened fine. A failed navigation, on the other hand, always loads WebView's error page *as a
 * new document* for the failing URL: `onPageStarted`, `doUpdateVisitedHistory` and
 * `onPageFinished` arrive with exactly that URL. So an error is held as *pending* when reported
 * and only surfaces once a document for that URL is loaded; same-document commits (History API,
 * fragments) never surface one, and anything left over is dropped as stale.
 *
 * The callbacks' relative order differs between WebView versions (the error may be reported
 * before or after `onPageStarted`), so both orders are handled.
 *
 * Main thread only (every WebViewClient callback used here runs on it).
 */
internal class MainFrameErrorTracker(private val maxPending: Int = MAX_PENDING) {

    /** Pending errors keyed by URL without fragment, oldest first. */
    private val pending = LinkedHashMap<String, PageLoadError>()

    /** A document load started (`onPageStarted`) and has not finished yet. */
    private var documentLoading = false

    /** A main-frame navigation is starting; errors reported before it can no longer surface. */
    fun onNavigationRequested() {
        pending.clear()
    }

    /** A main-frame request failed. It surfaces only if a document for its URL is then loaded. */
    fun onError(error: PageLoadError) {
        val key = key(error.url)
        // A certificate error explains a failure better than the generic network error that
        // cancelling the TLS handshake can produce for the same request.
        val existing = pending[key]
        if (existing?.certificate != null && error.certificate == null) return
        pending.remove(key)
        pending[key] = error
        while (pending.size > maxPending) pending.remove(pending.keys.first())
    }

    /**
     * A new document started loading for [url]. Returns true when an error is pending for it, i.e.
     * the document is that failure's error page.
     */
    fun onDocumentStarted(url: String?): Boolean {
        documentLoading = true
        return hasPendingError(url)
    }

    /** Whether an error is pending for [url]. */
    fun hasPendingError(url: String?): Boolean = url != null && key(url) in pending

    /**
     * The main frame committed [url]. Returns the error it shows when the commit belongs to a new
     * document loaded for a failed request; same-document commits never show one.
     */
    fun onCommitted(url: String?): PageLoadError? =
        if (documentLoading && url != null) pending.remove(key(url)) else null

    /**
     * The main frame finished loading [url]. Returns the error shown for it, if any. Every other
     * pending error belonged to a request that never displayed and is discarded.
     *
     * A finish without a started document (fragment navigation) changes nothing: errors of a
     * navigation still in flight must survive it.
     */
    fun onPageFinished(url: String?): PageLoadError? {
        if (!documentLoading) return null
        documentLoading = false
        val shown = url?.let { pending.remove(key(it)) }
        pending.clear()
        return shown
    }

    private fun key(url: String): String = url.substringBefore('#')

    private companion object {
        const val MAX_PENDING = 4
    }
}
