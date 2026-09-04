package com.elewashy.nexa.feature.browser.presentation.webview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.links.ValidLinkRepository
import com.elewashy.nexa.feature.browser.data.regex.RegexPatterns
import com.elewashy.nexa.feature.browser.data.scripts.ScriptRepository
import com.elewashy.nexa.feature.browser.data.scripts.ScriptType
import java.io.ByteArrayInputStream
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView client for URL interception, ad blocking, script injection and page
 * lifecycle events.
 */
@SuppressLint("MissingOnRenderProcessGone") // Implemented below; AndroidX lint misses the Kotlin override.
class NexaWebViewClient(
    private val appContext: Context,
    private val adBlockRepository: AdBlockRepository,
    private val validLinkRepository: ValidLinkRepository,
    private val scriptRepository: ScriptRepository,
    private val onPageStartedEvent: (url: String?, isImmersiveHost: Boolean) -> Unit = { _, _ -> },
    private val onPageFinishedEvent: () -> Unit = {},
    private val onNavigationConsumedEvent: () -> Unit = {},
    private val onUrlUpdatedEvent: (String?) -> Unit = {},
    private val onPageLoadErrorEvent: () -> Unit = {},
    /** Committed main-frame navigation, for history recording. */
    private val onVisitCommittedEvent: (url: String?, isReload: Boolean) -> Unit = { _, _ -> },
    /** The WebView's renderer process died; the host must replace the view. */
    private val onRenderProcessGoneEvent: () -> Unit = {},
) : WebViewClient() {

    /**
     * Set before a programmatic load (restore, home, back/forward step) so
     * the commit is not recorded as a fresh user visit. The commit still
     * reaches [onVisitCommittedEvent] — it is the tab's persistent URL source
     * of truth — but with reload semantics, which history recording skips.
     * Consumed by the next [doUpdateVisitedHistory].
     */
    var suppressNextVisitCommit = false

    /** A main-frame load error precedes this commit — skip recording it. */
    private var pendingErrorVisit = false

    /** Whether the next history commit belongs to a real document load. */
    private var documentLoadPending = false

    /** Previous commit exists; the first callback is always a document visit. */
    private var hasCommittedDocument = false

    companion object {
        private const val TAG = "NexaWebViewClient"
        private const val TRACE = "URLTrace"

        private const val AD_HOSTS_CACHE_MAX_SIZE = 512

        /** Bound regex input so pathological URLs can't stall the interceptor. */
        private const val MAX_REGEX_URL_CHARS = 2048

        private const val KEY_BROWSER_FALLBACK_URL = "browser_fallback_url"

        private val IMMERSIVE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
        private val EMPTY_BYTES = ByteArray(0)

        private fun blockedResponse() = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(EMPTY_BYTES),
        )

        private fun normalizeUrlHost(url: String?): String? = try {
            if (url.isNullOrBlank()) null else url.toUri().host?.trim('.')?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        /**
         * Fullscreen-first hosts where the toolbar stays hidden even outside
         * video fullscreen. Shared with `BrowserViewModel` so fullscreen
         * exit restores the right toolbar state.
         */
        fun isImmersiveUrl(url: String?): Boolean {
            val host = normalizeUrlHost(url) ?: return false
            return IMMERSIVE_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }

    private val adHostsCache: MutableSet<String> = ConcurrentHashMap.newKeySet(64)
    private val safeHostsCache: MutableSet<String> = ConcurrentHashMap.newKeySet(512)
    private val whitelist: Set<String> = setOf("google.com")
    private val combinedAdRegex: Regex = RegexPatterns.combinedRegex

    @Volatile
    private var currentPageHost: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return handleUrlLoading(view, request.url.toString())
    }

    private fun handleUrlLoading(view: WebView, url: String): Boolean {
        Log.d(TRACE, "[NAV] $url")

        // Sites open about:blank for popup/document.write flows; let the
        // WebView handle it instead of dead-ending the navigation.
        if (url.equals("about:blank", ignoreCase = true)) {
            return false
        }

        val uri = url.toUri()

        // Non-http(s) schemes dispatch BEFORE the allowlist check: intent://
        // URLs carry their target host, and an allowlisted host there must
        // still go through intent parsing rather than dead-end.
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") {
            onNavigationConsumedEvent()
            return if (scheme == "intent") handleIntentUrl(view, url) else dispatchExternalUrl(url, scheme)
        }

        val host = uri.host
        if (host != null && isGloballyWhitelisted(host)) return false

        if (shouldBlockUrl(url, host)) {
            onNavigationConsumedEvent()
            return true
        }

        return false
    }

    /** Hands a custom-scheme URL (tel:, mailto:, app://…) to the OS via ACTION_VIEW. */
    private fun dispatchExternalUrl(url: String, scheme: String): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app handles scheme '$scheme'; navigation ignored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch external URL", e)
        }
        return true
    }

    /**
     * Parses an intent:// URL and launches the target app. Falls back to the
     * page's browser_fallback_url or the Play Store listing when nothing
     * resolves; never crashes on malformed input.
     */
    private fun handleIntentUrl(view: WebView, url: String): Boolean {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.w(TAG, "Malformed intent:// URL ignored")
            return true
        }

        val fallbackUrl = try {
            intent.getStringExtra(KEY_BROWSER_FALLBACK_URL)
        } catch (e: Exception) {
            null
        }
        intent.removeExtra(KEY_BROWSER_FALLBACK_URL)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        // A page must never pin an explicit component; resolution decides.
        intent.component = null
        // Keep the selector inside the same package hint so it cannot be
        // used to bypass resolution of the main intent.
        intent.`package`?.let { pkg -> intent.selector?.setPackage(pkg) }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        return try {
            appContext.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            launchIntentFallback(view, fallbackUrl, intent.`package`)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch intent:// URL", e)
            true
        }
    }

    private fun launchIntentFallback(view: WebView, fallbackUrl: String?, packageName: String?) {
        // Prefer the page-provided http(s) fallback; otherwise offer the
        // Play Store listing of the missing app.
        if (!fallbackUrl.isNullOrBlank()) {
            val fallbackScheme = fallbackUrl.toUri().scheme?.lowercase()
            if (fallbackScheme == "http" || fallbackScheme == "https") {
                view.loadUrl(fallbackUrl)
                return
            }
        }
        if (!packageName.isNullOrBlank()) {
            try {
                val marketIntent = Intent(
                    Intent.ACTION_VIEW,
                    "market://details?id=$packageName".toUri()
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                appContext.startActivity(marketIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Play Store fallback failed for $packageName")
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // Chromium invokes this callback on an IO thread. Never read or mutate `view` here;
        // WebView methods are UI-thread-confined and newer WebView builds fail fast on misuse.
        val uri = request.url
        val host = uri.host
        val scheme = uri.scheme

        if (scheme == "about") return null

        val isWhitelistedRequest = if (host == null) {
            false
        } else if (request.isForMainFrame) {
            isGloballyWhitelisted(host)
        } else {
            isWhitelistedForPage(host, currentPageHost)
        }
        if (isWhitelistedRequest) return null

        if (host != null) {
            val norm = normalizeHost(host)
            if (safeHostsCache.contains(norm)) return null
            if (isHostCached(norm)) return blockedResponse()
            if (adBlockRepository.isAdHost(norm)) {
                if (adHostsCache.size < AD_HOSTS_CACHE_MAX_SIZE) adHostsCache.add(norm)
                return blockedResponse()
            }
        }

        val url = uri.toString().take(MAX_REGEX_URL_CHARS)
        return try {
            if (combinedAdRegex.matches(url)) {
                blockedResponse()
            } else {
                if (host != null && safeHostsCache.size < 512) safeHostsCache.add(normalizeHost(host))
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // onPageStarted is delivered for real document loads (including
        // redirects/reloads), but not History API or fragment-only changes.
        documentLoadPending = true
        pendingErrorVisit = false
        scriptRepository.inject(view, ScriptType.PRE_LOAD)
        currentPageHost = normalizeUrlHost(url)
        onPageStartedEvent(url, isImmersiveUrl(url))
        onUrlUpdatedEvent(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.requestLayout()
        scriptRepository.inject(view, ScriptType.POST_LOAD)
        onPageFinishedEvent()
        onUrlUpdatedEvent(url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        currentPageHost = normalizeUrlHost(url)
        scriptRepository.inject(view, ScriptType.PRE_LOAD)
        onUrlUpdatedEvent(url)

        // URL comparison cannot identify same-document navigation: pushState
        // may change path and query. The page-start signal is the document
        // identity boundary; fragment/History API commits arrive without it.
        val sameDocument = hasCommittedDocument && !documentLoadPending
        hasCommittedDocument = true
        documentLoadPending = false

        val programmatic = suppressNextVisitCommit
        suppressNextVisitCommit = false
        val errorVisit = pendingErrorVisit
        pendingErrorVisit = false
        when {
            // A failed load never became a page the user reached.
            errorVisit -> Unit
            // Programmatic loads and same-document commits persist the tab
            // URL but are not user visits: reload semantics make history
            // recording skip them.
            programmatic || sameDocument -> onVisitCommittedEvent(url, true)
            else -> onVisitCommittedEvent(url, isReload)
        }
    }

    /**
     * A renderer crash (or renderer OOM kill) must not take the whole app
     * down — every WebView here shares one renderer process by default.
     * Returning true tells the system the app handled it; the host replaces
     * the dead WebView.
     */
    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        Log.w(
            TAG,
            "Renderer process gone (crash=${detail.didCrash()}) — replacing WebView"
        )
        onRenderProcessGoneEvent()
        return true
    }

    // Error-overlay policy: the overlay only covers failures that leave the
    // user without the requested PAGE — real main-frame network errors and
    // main-frame SSL failures. Subresource errors, benign aborts, and HTTP
    // 4xx/5xx (the server renders its own error page) never trigger it.

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        // ERR_ABORTED fires for benign cancellations (download handoffs,
        // quick redirects) — not a real load failure. WebViewClient has no
        // constant for it; the chromium description is the stable signal.
        if (error.description?.toString()?.contains("ERR_ABORTED") == true) return
        pendingErrorVisit = true
        onPageLoadErrorEvent()
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        // Intentionally no error overlay for HTTP 4xx/5xx.
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Log.w(TAG, "Cancelling navigation due to SSL error: ${error.url}")
        // Never proceed past a certificate failure.
        handler.cancel()
        // Only a main-frame failure strands the user on a dead page; a
        // subresource SSL error keeps the page itself, so skip the overlay.
        val errorHost = normalizeUrlHost(error.url)?.let(::normalizeHost)
        val pageHost = currentPageHost?.let(::normalizeHost)
        if (errorHost != null && errorHost == pageHost) {
            onPageLoadErrorEvent()
        }
    }

    private fun normalizeHost(host: String): String = host.trim('.').lowercase().removePrefix("www.")

    private fun isGloballyWhitelisted(host: String): Boolean {
        val norm = normalizeHost(host)
        return whitelist.any { norm == it || norm.endsWith(".$it") } || validLinkRepository.isValidHost(host)
    }

    private fun isWhitelistedForPage(host: String, pageHost: String?): Boolean {
        val norm = normalizeHost(host)
        return whitelist.any { norm == it || norm.endsWith(".$it") } || validLinkRepository.isValidHostOnPage(host, pageHost)
    }

    private fun shouldBlockUrl(url: String, host: String?): Boolean {
        host ?: return false
        val norm = normalizeHost(host)
        if (isHostCached(norm)) return true
        if (adBlockRepository.isAdHost(norm)) {
            if (adHostsCache.size < AD_HOSTS_CACHE_MAX_SIZE) adHostsCache.add(norm)
            return true
        }
        return try {
            combinedAdRegex.matches(url.take(MAX_REGEX_URL_CHARS))
        } catch (e: Exception) {
            Log.e(TAG, "Regex error for: $url", e)
            false
        }
    }

    private fun isHostCached(host: String): Boolean {
        if (host in adHostsCache) return true
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1) {
            val parent = host.substring(dotIndex + 1)
            if (parent.indexOf('.') == -1) break
            if (parent in adHostsCache) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

}
