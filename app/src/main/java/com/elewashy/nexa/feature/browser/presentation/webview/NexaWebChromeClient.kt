package com.elewashy.nexa.feature.browser.presentation.webview

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * NexaWebChromeClient — Handles fullscreen video and page-load progress.
 *
 * Responsibilities:
 *  - Enter / exit fullscreen (landscape, immersive, system-bar hiding)
 *  - Progress-bar updates (forwarded to ViewModel → Compose UI)
 *
 * Toolbar visibility, keep-screen-on, and progress-bar rendering are now
 * driven by the [onFullscreenEnter] / [onFullscreenExit] /
 * [onProgressChanged] event callbacks (forwarded to `BrowserViewModel`).
 * The [Activity] reference is kept only for genuinely Activity-scoped
 * operations: `requestedOrientation`, `window`, and system-bar insets.
 *
 * The custom-view state is managed internally. Call [cleanUpFullscreen]
 * from the host's teardown to ensure orientation and system bars are
 * restored.
 *
 * @param activity               Provides orientation / window ops
 * @param webView                The [WebView] to toggle visibility on fullscreen
 * @param customViewContainer    [FrameLayout] that hosts the fullscreen view
 * @param rootView               Root view for [WindowInsetsControllerCompat]
 * @param onProgressChangedEvent VM event: progress-bar percent (0–100)
 * @param onFullscreenEnter      VM event: fullscreen custom view shown
 * @param onFullscreenExit       VM event: fullscreen custom view hidden
 * @param onProgressComplete     Callback when progress reaches 100 %
 */
class NexaWebChromeClient(
    private val activity: Activity,
    private val webView: WebView,
    private val customViewContainer: FrameLayout,
    private val rootView: View,
    private val onProgressChangedEvent: (Int) -> Unit = {},
    private val onFullscreenEnter: () -> Unit = {},
    private val onFullscreenExit: () -> Unit = {},
    private val onProgressComplete: () -> Unit = {}
) : WebChromeClient() {

    companion object {
        private const val TAG = "NexaWebChromeClient"
    }

    // ── Fullscreen state ───────────────────────────────────────

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** `true` while a fullscreen custom view is showing. */
    val isFullscreen: Boolean get() = customView != null

    // ────────────────────────────────────────────────────────────
    //  Fullscreen lifecycle
    // ────────────────────────────────────────────────────────────

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            onHideCustomView()
            return
        }

        customView = view
        customViewCallback = callback
        originalOrientation = activity.requestedOrientation

        onFullscreenEnter()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        customViewContainer.addView(view)
        customViewContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE

        hideSystemBars()
    }

    override fun onHideCustomView() {
        val current = customView ?: return

        try {
            onFullscreenExit()
            activity.requestedOrientation = originalOrientation

            webView.visibility = View.VISIBLE
            customViewContainer.visibility = View.GONE
            customViewContainer.removeView(current)

            customViewCallback?.onCustomViewHidden()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding custom view: ${e.message}", e)
        } finally {
            customView = null
            customViewCallback = null
        }

        showSystemBars()
    }

    /**
     * Call from the host's teardown to guarantee
     * fullscreen state is cleaned up.
     */
    fun cleanUpFullscreen() {
        if (customView == null) return

        try {
            customViewContainer.removeAllViews()
            customViewContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            customViewCallback?.onCustomViewHidden()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up fullscreen: ${e.message}", e)
        }
        customView = null
        customViewCallback = null

        activity.requestedOrientation = originalOrientation
        onFullscreenExit()
        showSystemBars()
    }

    // ────────────────────────────────────────────────────────────
    //  Progress
    // ────────────────────────────────────────────────────────────

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedEvent(newProgress)
        if (newProgress == 100) {
            onProgressComplete()
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        Log.w(TAG, "Denied site permission request from ${request?.origin}")
        request?.deny()
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        // Intentionally empty — favicon view was removed
    }

    // ────────────────────────────────────────────────────────────
    //  System bars helpers
    // ────────────────────────────────────────────────────────────

    private fun hideSystemBars() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, rootView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, rootView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
