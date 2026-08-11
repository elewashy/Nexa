package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.core.app.ShareCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.presentation.screen.ContextMenuAction
import java.util.concurrent.atomic.AtomicInteger

sealed interface ContextMenuResult {
    data object None : ContextMenuResult
    data class Message(val text: String) : ContextMenuResult
    data class Base64Image(val dataUrl: String) : ContextMenuResult
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
     */
    fun onActionSelected(
        action: ContextMenuAction,
        webView: WebView,
        context: Context,
        onDownloadStarted: (() -> Unit)? = null,
    ): ContextMenuResult {
        // Data captured at long-press time; still null if the WebView has
        // not delivered it yet — every action below degrades gracefully.
        val node = focusNodeData
        val url = node?.getString("url")
        val imgUrl = node?.getString("src")

        Log.d(TAG, "Action: ${action.name}, url=$url, imgUrl=$imgUrl")

        return when (action) {
            ContextMenuAction.VIEW_IMAGE -> handleViewImage(imgUrl, webView)
            ContextMenuAction.SAVE_IMAGE -> handleSaveImage(imgUrl, webView, context, onDownloadStarted)
            ContextMenuAction.SHARE      -> handleShare(url ?: imgUrl, context)
            ContextMenuAction.CLOSE      -> ContextMenuResult.None
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Action handlers (unchanged from original)
    // ────────────────────────────────────────────────────────────

    private fun handleViewImage(imgUrl: String?, webView: WebView): ContextMenuResult {
        imgUrl ?: return ContextMenuResult.None
        return if (isBase64DataUrl(imgUrl)) {
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
        onDownloadStarted: (() -> Unit)?,
    ): ContextMenuResult {
        imgUrl ?: return ContextMenuResult.None
        return if (isBase64DataUrl(imgUrl)) {
            val bitmap = decodeBase64Image(imgUrl) ?: return ContextMenuResult.None
            val uri = ImageSaver.saveToGallery(
                context, bitmap, "Nexa_Image_${System.currentTimeMillis()}"
            )
            val msg = if (uri != null) {
                context.getString(R.string.image_saved_to_gallery)
            } else {
                context.getString(R.string.failed_to_save_image)
            }
            ContextMenuResult.Message(msg)
        } else {
            // Download via app's DownloadHandler instead of external view intent
            DownloadHandler.startDownload(
                context = context,
                url = imgUrl,
                mimeType = "image/*",
                userAgent = webView.settings.userAgentString,
                currentPageUrl = webView.url,
                onDownloadStarted = onDownloadStarted,
            )
            ContextMenuResult.None
        }
    }

    private fun handleShare(tempUrl: String?, context: Context): ContextMenuResult {
        if (tempUrl == null) {
            return ContextMenuResult.Message(context.getString(R.string.invalid_link))
        }
        return if (isBase64DataUrl(tempUrl)) {
            val bitmap = decodeBase64Image(tempUrl) ?: return ContextMenuResult.None
            val uri = ImageSaver.saveToGallery(
                context, bitmap, "Nexa_Share_${System.currentTimeMillis()}"
            )
            if (uri != null) {
                ShareCompat.IntentBuilder(context)
                    .setChooserTitle(context.getString(R.string.sharing_image))
                    .setType("image/*")
                    .setStream(uri)
                    .startChooser()
                ContextMenuResult.None
            } else {
                ContextMenuResult.Message(context.getString(R.string.failed_to_save_image_for_sharing))
            }
        } else {
            ShareCompat.IntentBuilder(context)
                .setChooserTitle(context.getString(R.string.sharing_url))
                .setType("text/plain")
                .setText(tempUrl)
                .startChooser()
            ContextMenuResult.None
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────

    /** True for inline base64 data-URIs; a plain substring match on
     *  "base64" also caught ordinary URLs that merely contain the word. */
    private fun isBase64DataUrl(url: String?): Boolean =
        url != null && url.startsWith("data:") && url.contains(";base64,")

    fun decodeBase64Image(dataUrl: String): Bitmap? {
        return try {
            val pureBytes = dataUrl.substring(dataUrl.indexOf(",") + 1)
            val decoded = Base64.decode(pureBytes, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (_: Exception) {
            null
        }
    }
}
