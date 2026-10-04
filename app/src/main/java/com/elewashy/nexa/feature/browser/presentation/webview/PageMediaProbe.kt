package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the in-page media probe (`assets/media/page_media_probe.js`) that
 * confirms whether a tweet, Threads post or Facebook post actually renders
 * downloadable media.
 *
 * Follows the androidx.webkit guidance for app ↔ page messaging:
 * [WebViewCompat.addDocumentStartJavaScript] and
 * [WebViewCompat.addWebMessageListener] are both restricted to the
 * platforms' HTTPS origins, so no other site sees the listener or runs the
 * script, and no `addJavascriptInterface` object is exposed. Messages from
 * subframes or with a URL outside the sending origin are ignored.
 */
@Singleton
class PageMediaProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val script: String? by lazy(::readScript)

    /**
     * Registers the probe on [webView] before its first load. [onReport]
     * runs on the UI thread with the reported page URL and whether it has
     * media. Returns false when this WebView cannot run the probe.
     */
    @MainThread
    fun install(webView: WebView, onReport: (url: String, hasMedia: Boolean) -> Unit): Boolean {
        val source = script ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        try {
            WebViewCompat.addWebMessageListener(webView, LISTENER_NAME, ALLOWED_ORIGIN_RULES) {
                    _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && message.type == WebMessageCompat.TYPE_STRING) {
                    parse(message.data, sourceOrigin.host)?.let { (url, hasMedia) -> onReport(url, hasMedia) }
                }
            }
        } catch (e: RuntimeException) {
            // Defensive: an invalid rule or a WebView provider bug must never break tab creation.
            Log.w(TAG, "Media probe listener unavailable", e)
            return false
        }
        return try {
            WebViewCompat.addDocumentStartJavaScript(webView, source, ALLOWED_ORIGIN_RULES)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Media probe script unavailable", e)
            WebViewCompat.removeWebMessageListener(webView, LISTENER_NAME)
            false
        }
    }

    private fun readScript(): String? = try {
        context.assets.open(SCRIPT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        Log.e(TAG, "Missing media probe script", e)
        null
    }

    internal companion object {
        private const val TAG = "PageMediaProbe"
        private const val SCRIPT_ASSET = "media/page_media_probe.js"

        /** JS global the probe posts to; must match the script. */
        const val LISTENER_NAME = "__NEXA_PROBE__"

        /** Platforms whose content pages need in-page confirmation (see `MediaPresence`). */
        private val PROBED_DOMAINS = listOf("x.com", "twitter.com", "threads.net", "threads.com", "facebook.com")

        val ALLOWED_ORIGIN_RULES: Set<String> = PROBED_DOMAINS
            .flatMapTo(LinkedHashSet()) { listOf("https://$it", "https://*.$it") }

        /**
         * Decodes a probe message. The URL must belong to [originHost] so a
         * page can only ever report about itself.
         */
        fun parse(data: String?, originHost: String?): Pair<String, Boolean>? {
            if (data.isNullOrEmpty() || data.length > MAX_MESSAGE_LENGTH || originHost.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(data)
                val url = json.getString("url")
                val hasMedia = json.getBoolean("hasMedia")
                if (URI(url).host.equals(originHost, ignoreCase = true)) url to hasMedia else null
            } catch (_: JSONException) {
                null
            } catch (_: URISyntaxException) {
                null
            }
        }

        private const val MAX_MESSAGE_LENGTH = 8 * 1024
    }
}
