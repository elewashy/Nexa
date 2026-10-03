package com.elewashy.nexa.feature.browser.presentation.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Handles `onCreateWindow` — `window.open()` and `target=_blank` links.
 *
 * WebView only reveals a popup's URL once it starts loading in a WebView we
 * supply, so each request gets a detached, never-attached "probe" WebView
 * that captures the first real URL and loads nothing (every probe request
 * is answered locally, so a rejected ad popup never even reaches the
 * network). [WebViewContentBlocker.allowPopup] then decides: allowed popups
 * open as a new tab, rejected ones are dropped silently — the opener page is
 * never replaced.
 *
 * All methods run on the UI thread.
 */
class PopupWindowHandler(
    private val contentBlocker: WebViewContentBlocker,
    /** Creates a WebView in the opener's profile (private tabs need the private profile). */
    private val createProbeWebView: () -> WebView,
    private val openInNewTab: (url: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onCreateWindow(opener: WebView, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // Chromium's popup blocker already stops gesture-less window.open
        // (javaScriptCanOpenWindowsAutomatically = false); refuse anything else that slips through.
        if (!isUserGesture) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val tappedLink = tappedLinkUrl(opener)
        val probe = try {
            createProbeWebView()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Popup probe unavailable", e)
            return false
        }
        val session = ProbeSession(probe, tappedLink)
        probe.webViewClient = session
        transport.webView = probe
        resultMsg.sendToTarget()
        mainHandler.postDelayed(session::expire, PROBE_TIMEOUT_MS)
        return true
    }

    private fun dispose(probe: WebView) {
        if (probe.tag == DISPOSED) return
        probe.tag = DISPOSED
        probe.stopLoading()
        probe.destroy()
    }

    private fun tappedLinkUrl(view: WebView): String? {
        val hit = view.hitTestResult
        return if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) hit.extra else null
    }

    /** Captures the popup's first http(s) URL, then disposes of the probe. */
    @SuppressLint("MissingOnRenderProcessGone") // Implemented below; AndroidX lint misses the Kotlin override.
    private inner class ProbeSession(
        private val probe: WebView,
        private val tappedLink: String?,
    ) : WebViewClient() {
        private var done = false

        fun expire() {
            if (!done) {
                Log.d(TAG, "Popup never navigated; discarding")
                finish(null)
            }
        }

        private fun finish(url: String?) {
            if (done) return
            done = true
            // Destroy after the current callback returns; WebView forbids destroy() re-entrantly.
            mainHandler.post { dispose(probe) }
            if (url != null && contentBlocker.allowPopup(url, tappedLink)) openInNewTab(url)
        }

        private fun capture(url: String?) {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return
            finish(url)
        }

        /** A renderer crash must not take the app down; the probe is disposable. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            finish(null)
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            capture(request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            capture(url)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            // IO thread: hand the URL to the UI thread and answer locally — nothing reaches the network.
            if (request.isForMainFrame) {
                val url = request.url.toString()
                mainHandler.post { capture(url) }
            }
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(EMPTY))
        }
    }

    private companion object {
        const val TAG = "PopupWindowHandler"
        const val PROBE_TIMEOUT_MS = 3_000L
        val DISPOSED = Any()
        val EMPTY = ByteArray(0)
    }
}
