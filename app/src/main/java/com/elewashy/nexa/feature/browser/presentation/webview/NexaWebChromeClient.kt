package com.elewashy.nexa.feature.browser.presentation.webview

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
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
 *  - Enter / exit fullscreen (sensor orientation, immersive, system-bar hiding)
 *  - Progress-bar updates (forwarded to ViewModel → Compose UI)
 *  - File uploads via [onShowFileChooser] / [onFileChooserResult]
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
 * @param fileChooserLauncher    Launches an `ACTION_GET_CONTENT` intent for
 *                               `<input type=file>` uploads and returns `true`
 *                               if a picker was actually started. `null` (the
 *                               default) cancels the chooser. The host must
 *                               deliver the picker result via
 *                               [onFileChooserResult].
 */
class NexaWebChromeClient(
    private val activity: Activity,
    private val webView: WebView,
    private val customViewContainer: FrameLayout,
    private val rootView: View,
    private var onProgressChangedEvent: (Int) -> Unit = {},
    private var onFullscreenEnter: () -> Unit = {},
    private var onFullscreenExit: () -> Unit = {},
    private var onProgressComplete: () -> Unit = {},
    private var onReceivedTitleEvent: (url: String?, title: String?) -> Unit = { _, _ -> },
    private var onReceivedIconEvent: (url: String?, icon: Bitmap?) -> Unit = { _, _ -> },
    private var fileChooserLauncher: ((Intent) -> Boolean)? = null,
    /** Whether this client's WebView is the attached (visible) tab. */
    private val isAttachedToUi: () -> Boolean = { true },
) : WebChromeClient() {

    companion object {
        private const val TAG = "NexaWebChromeClient"

        private val GRANTED_PERMISSIONS = setOf(
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
        )
    }

    // ── Fullscreen state ───────────────────────────────────────

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** `true` while a fullscreen custom view is showing. */
    val isFullscreen: Boolean get() = customView != null

    fun updateCallbacks(
        onProgressChangedEvent: (Int) -> Unit,
        onFullscreenEnter: () -> Unit,
        onFullscreenExit: () -> Unit,
        onProgressComplete: () -> Unit,
        onReceivedTitleEvent: (url: String?, title: String?) -> Unit = this.onReceivedTitleEvent,
    ) {
        this.onProgressChangedEvent = onProgressChangedEvent
        this.onFullscreenEnter = onFullscreenEnter
        this.onFullscreenExit = onFullscreenExit
        this.onProgressComplete = onProgressComplete
        this.onReceivedTitleEvent = onReceivedTitleEvent
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        onReceivedTitleEvent(view?.url, title)
    }

    // ────────────────────────────────────────────────────────────
    //  Fullscreen lifecycle
    // ────────────────────────────────────────────────────────────

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        // A background tab (paused but still running media/JS) must not paint
        // its fullscreen view over the active tab via the shared container.
        if (!isAttachedToUi()) {
            callback.onCustomViewHidden()
            return
        }
        if (customView != null) {
            // A stale fullscreen view is still up (e.g. the renderer swapped
            // video surfaces). Tear it down and continue with the new view
            // instead of dropping it.
            onHideCustomView()
        }

        customView = view
        customViewCallback = callback
        originalOrientation = activity.requestedOrientation

        onFullscreenEnter()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        customViewContainer.addView(view)
        customViewContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE

        hideSystemBars()
    }

    override fun onHideCustomView() {
        val current = customView ?: return
        val callback = customViewCallback
        // Clear ownership before invoking Chromium's callback: it may call
        // onHideCustomView synchronously, and must observe an idempotent exit.
        customView = null
        customViewCallback = null

        try {
            onFullscreenExit()
            activity.requestedOrientation = originalOrientation
            webView.visibility = View.VISIBLE
            customViewContainer.visibility = View.GONE
            customViewContainer.removeView(current)
            callback?.onCustomViewHidden()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding custom view: ${e.message}", e)
        } finally {
            showSystemBars()
        }
    }

    /**
     * Call from the host's teardown to guarantee
     * fullscreen state is cleaned up.
     */
    fun cleanUpFullscreen() {
        // Use the same idempotent ownership release as a normal Chromium exit.
        // Maintaining a second teardown path previously invoked the callback
        // before clearing state, allowing a reentrant onHideCustomView call.
        onHideCustomView()
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
        request ?: return
        // Explicit allow-list: DRM playback and WebRTC capture are
        // site-gesture-initiated; every other WebView permission stays
        // denied.
        val granted = request.resources.filter { it in GRANTED_PERMISSIONS }
        if (granted.isEmpty()) {
            Log.w(TAG, "Denied site permission request from ${request.origin}")
            request.deny()
        } else {
            request.grant(granted.toTypedArray())
        }
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        onReceivedIconEvent(view?.url, icon)
    }

    // ────────────────────────────────────────────────────────────
    //  File uploads (<input type=file>)
    // ────────────────────────────────────────────────────────────

    /** Result callback for the currently pending file chooser, if any. */
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null

    /** Allows the host to (re)wire the launcher after construction. */
    fun updateFileChooserLauncher(launcher: ((Intent) -> Boolean)?) {
        fileChooserLauncher = launcher
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        // Each callback may receive a value exactly once; cancel a previous
        // chooser that never got a result so the WebView doesn't leak it.
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null

        val launcher = fileChooserLauncher
        if (filePathCallback == null || launcher == null) {
            filePathCallback?.onReceiveValue(null)
            return true
        }

        val launched = try {
            launcher(buildFileChooserIntent(fileChooserParams))
        } catch (e: Exception) {
            Log.e(TAG, "File chooser launcher failed: ${e.message}", e)
            false
        }

        if (launched) {
            pendingFilePathCallback = filePathCallback
        } else {
            filePathCallback.onReceiveValue(null)
        }
        return true
    }

    /**
     * Delivers the picker result from the host's activity-result callback.
     * Call with the picker's [resultCode] and `data` intent (or `null` data
     * when the user cancelled).
     */
    fun onFileChooserResult(data: Intent?, resultCode: Int = Activity.RESULT_OK) {
        val callback = pendingFilePathCallback ?: return
        pendingFilePathCallback = null
        val uris = try {
            // parseResult unwraps single- and multi-select pickers alike —
            // EXTRA_ALLOW_MULTIPLE selections arrive as clip data, which a
            // plain data?.data read would collapse to the first item.
            FileChooserParams.parseResult(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file chooser result: ${e.message}", e)
            null
        }
        callback.onReceiveValue(uris)
    }

    private fun buildFileChooserIntent(params: FileChooserParams?): Intent {
        // Pass the page's accept types (e.g. video/*, image/*) as hints;
        // the picker itself stays */* so users are never dead-ended.
        val mimeTypes = params?.acceptTypes
            ?.mapNotNull { type ->
                type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.contains('/') }
            }
            .orEmpty()

        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (mimeTypes.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
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
        // Only reveal the insets. Re-enabling decor-fits here would undo
        // enableEdgeToEdge and double-inset the content until recreation.
        WindowInsetsControllerCompat(window, rootView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
