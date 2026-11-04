package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.webkit.WebView
import androidx.core.app.ShareCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.data.image.DataUrlImage
import com.elewashy.nexa.feature.browser.presentation.screen.ContextMenuAction
import java.util.concurrent.atomic.AtomicInteger

sealed interface ContextMenuResult {
    data object None : ContextMenuResult
    data class Message(val text: String) : ContextMenuResult
    data class Base64Image(val dataUrl: String) : ContextMenuResult

    /** Save an inline image to the gallery; blocking I/O the caller runs off the main thread. */
    data class SaveInlineImage(val dataUrl: String) : ContextMenuResult

    /** Save an inline image, then share it; the caller runs the save off the main thread. */
    data class ShareInlineImage(val dataUrl: String) : ContextMenuResult
}

/**
 * ContextMenuHandler — Builds and dispatches context-menu actions
 * for the WebView.
 *
 * Flow:
 *  1. [getContextMenuActions] determines available actions from hit-test
 *     and kicks off the async href/src probe (long-press time)
 *  2. Caller shows a Compose [ContextMenuScreen] with the returned actions
 *  3. User taps an action → [onActionSelected] dispatches it using the
 *     probe result captured at step 1 — never blocking the UI thread
 *
 * Everything here runs on the UI thread. Inline (`data:`) image work — decoding and
 * writing to MediaStore — is returned as [ContextMenuResult.SaveInlineImage] /
 * [ContextMenuResult.ShareInlineImage] for the caller to run in the background.
 */
object ContextMenuHandler {

    private const val TAG = "ContextMenuHandler"

    /** Latest href/src data delivered for the current context menu, if any. */
    @Volatile
    private var focusNodeData: Bundle? = null

    /** Bumped per probe so a late delivery from a previous menu is dropped. */
    private val focusNodeProbeToken = AtomicInteger(0)

    // requestFocusNodeHref delivers its data asynchronously to a Handler; a
    // dedicated thread keeps that delivery off the UI thread's queue. The
    // probe starts at long-press time, so action clicks read the stored
    // result instead of blocking on the delivery.
    private val focusNodeThread = HandlerThread("ContextMenuHref").apply { start() }
    private val focusNodeHandler = Handler(focusNodeThread.looper) { msg ->
        if (msg.arg1 == focusNodeProbeToken.get()) {
            focusNodeData = msg.data
        }
        true
    }

    // ────────────────────────────────────────────────────────────
    //  Determine available actions
    // ────────────────────────────────────────────────────────────

    /**
     * Determines the available actions based on the WebView's hit-test
     * result. Returns an empty list when no menu should be shown.
     *
     * @param webView The WebView that received the long-press
     * @return List of [ContextMenuAction]s, or empty if no menu applies
     */
    fun getContextMenuActions(webView: WebView): List<ContextMenuAction> {
        val hitResult = webView.hitTestResult

        val actions = when (hitResult.type) {
            WebView.HitTestResult.IMAGE_TYPE -> listOf(
                ContextMenuAction.VIEW_IMAGE,
                ContextMenuAction.SAVE_IMAGE,
                ContextMenuAction.SHARE,
                ContextMenuAction.CLOSE
            )
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> listOf(
                ContextMenuAction.SHARE,
                ContextMenuAction.CLOSE
            )
            WebView.HitTestResult.EDIT_TEXT_TYPE,
            WebView.HitTestResult.UNKNOWN_TYPE -> emptyList()
            else -> listOf(
                ContextMenuAction.SHARE,
                ContextMenuAction.CLOSE
            )
        }

        if (actions.isNotEmpty()) {
            beginFocusNodeProbe(webView)
        }
        return actions
    }

    /**
     * Starts the focused-node href/src probe while the menu is being shown,
     * so action clicks never wait for the WebView's async delivery.
     * Must run on the UI thread (the long-press listener).
     */
    private fun beginFocusNodeProbe(webView: WebView) {
        focusNodeData = null
        val message = focusNodeHandler.obtainMessage().apply {
            arg1 = focusNodeProbeToken.incrementAndGet()
        }
        webView.requestFocusNodeHref(message)
    }

    // ────────────────────────────────────────────────────────────
    //  Action dispatch
    // ────────────────────────────────────────────────────────────

    /**
     * Handles the selected action from the bottom sheet.
     *
     * @param action   The selected [ContextMenuAction]
     * @param webView  The WebView for hit-test data
     * @param context  Context for intents and dialogs
     * @param onDownloadStarted Optional UI callback for downloaded images.
     * @param launchDownload Runs a download once its permissions are in place.
     */
    fun onActionSelected(
        action: ContextMenuAction,
        webView: WebView,
        context: Context,
        onDownloadStarted: ((StartedDownload) -> Unit)? = null,
        launchDownload: (download: () -> Unit) -> Unit = { it() },
    ): ContextMenuResult {
        // Data captured at long-press time; still null if the WebView has
        // not delivered it yet — every action below degrades gracefully.
        val node = focusNodeData
        val url = node?.getString("url")
        val imgUrl = node?.getString("src")

        Log.d(TAG, "Action: ${action.name}, url=$url, imgUrl=$imgUrl")

        return when (action) {
            ContextMenuAction.VIEW_IMAGE -> handleViewImage(imgUrl, webView)
            ContextMenuAction.SAVE_IMAGE ->
                handleSaveImage(imgUrl, webView, context, onDownloadStarted, launchDownload)
            ContextMenuAction.SHARE      -> handleShare(url ?: imgUrl, context)
            ContextMenuAction.CLOSE      -> ContextMenuResult.None
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Action handlers
    // ────────────────────────────────────────────────────────────

    private fun handleViewImage(imgUrl: String?, webView: WebView): ContextMenuResult {
        imgUrl ?: return ContextMenuResult.None
        return if (DataUrlImage.isBase64Image(imgUrl)) {
            ContextMenuResult.Base64Image(imgUrl)
        } else {
            webView.loadUrl(imgUrl)
            ContextMenuResult.None
        }
    }

    private fun handleSaveImage(
        imgUrl: String?,
        webView: WebView,
        context: Context,
        onDownloadStarted: ((StartedDownload) -> Unit)?,
        launchDownload: (download: () -> Unit) -> Unit,
    ): ContextMenuResult {
        imgUrl ?: return ContextMenuResult.None
        return if (DataUrlImage.isBase64Image(imgUrl)) {
            ContextMenuResult.SaveInlineImage(imgUrl)
        } else {
            // Download via app's DownloadHandler instead of external view intent
            val userAgent = webView.settings.userAgentString
            val pageUrl = webView.url
            launchDownload {
                DownloadHandler.startDownload(
                    context = context,
                    url = imgUrl,
                    mimeType = "image/*",
                    userAgent = userAgent,
                    currentPageUrl = pageUrl,
                    onDownloadStarted = onDownloadStarted,
                )
            }
            ContextMenuResult.None
        }
    }

    private fun handleShare(tempUrl: String?, context: Context): ContextMenuResult {
        if (tempUrl == null) {
            return ContextMenuResult.Message(context.getString(R.string.invalid_link))
        }
        if (DataUrlImage.isBase64Image(tempUrl)) {
            return ContextMenuResult.ShareInlineImage(tempUrl)
        }
        ShareCompat.IntentBuilder(context)
            .setChooserTitle(context.getString(R.string.sharing_url))
            .setType("text/plain")
            .setText(tempUrl)
            .startChooser()
        return ContextMenuResult.None
    }
}
