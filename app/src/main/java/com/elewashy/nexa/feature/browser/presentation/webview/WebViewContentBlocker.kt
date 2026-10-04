package com.elewashy.nexa.feature.browser.presentation.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.elewashy.nexa.feature.adblock.data.AdBlockAssets
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicy
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicyStore
import com.elewashy.nexa.feature.adblock.data.AdBlockRepository
import com.elewashy.nexa.feature.adblock.data.AdBlockStatsRecorder
import com.elewashy.nexa.feature.adblock.data.SitePolicy
import com.elewashy.nexa.feature.adblock.data.PopupHint
import com.elewashy.nexa.feature.adblock.data.PopupHints
import com.elewashy.nexa.feature.adblock.data.PopupPolicy
import com.elewashy.nexa.feature.adblock.data.engine.FilterEngine
import com.elewashy.nexa.feature.adblock.data.engine.Hostnames
import com.elewashy.nexa.feature.adblock.data.engine.MatchResult
import com.elewashy.nexa.feature.adblock.data.engine.PageContext
import com.elewashy.nexa.feature.adblock.data.engine.RequestType
import com.elewashy.nexa.feature.adblock.data.engine.RequestTypeResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies the content-blocking engine to one WebView — the WebView
 * counterpart of an extension's background + content scripts:
 *
 *  - **Network filtering** in [intercept] (`shouldInterceptRequest`, IO
 *    threads): every subresource is matched against the engine with its
 *    inferred resource type, HTTP method and the context of the document
 *    that issued it. Blocked requests fail like a cancelled request (a
 *    non-2xx empty response, so `onerror` fires as it would in uBO);
 *    `redirect=` filters serve the neutered surrogate instead.
 *  - **Document filtering**: top-level documents the user navigates to that
 *    match `$document` or strict hostname filters get an explanatory block
 *    page with a one-time "proceed" option. Page loads whose URL carries
 *    parameters targeted by `$removeparam` filters are answered with a tiny
 *    document that replaces itself with the cleaned URL (WebView cannot
 *    rewrite a navigation in flight). Navigations a *page* starts
 *    (scripts, frame-busting) to such documents, or matching `$popup`
 *    filters, are cancelled silently ([checkNavigation]) so the user stays
 *    on the page instead of landing on a block page.
 *  - **Popups**: new windows are attributed to the frame that opened them
 *    (reported by the content script) and judged by [PopupPolicy]
 *    ([allowPopup]).
 *  - **Cosmetic filtering, scriptlets & `$csp`**: one static
 *    document-start script (registered for all frames) asks [Bridge] for the
 *    frame's payload; the engine returns only what applies to that frame, so
 *    most frames receive a stylesheet and run no scriptlet at all.
 *
 * Before the engine is consulted, the switches of the page's site apply
 * ([AdBlockPolicyStore], uBO's trusted sites and per-site switches): with
 * the blocker off globally or for the top document's site nothing is
 * filtered — no network blocking, no block page, no `$removeparam`, no
 * cosmetics or scriptlets, no popup blocking — for the page and all of its
 * frames. Cosmetic filtering and popup blocking can also be turned off
 * individually per site.
 *
 * Blocking statistics are recorded through [AdBlockStatsRecorder] (batched,
 * in memory); [blockedOnPage] counts what was blocked on the current page.
 * Blocked hosts from private tabs ([isPrivate]) are never recorded.
 *
 * Thread-safety: page state is published through volatile fields; the
 * engine and the policy snapshot are immutable.
 */
class WebViewContentBlocker(
    private val repository: AdBlockRepository,
    private val policyStore: AdBlockPolicyStore,
    private val stats: AdBlockStatsRecorder,
    private val assets: AdBlockAssets,
    private val blockPage: BlockedPageRenderer,
    private val isPrivate: Boolean,
) {
    private class PageState(
        val url: String,
        val engine: FilterEngine,
        val context: PageContext,
        val policies: AdBlockPolicy,
        /** Switches of the top document's site; they cover every frame of the page. */
        val policy: SitePolicy,
    )

    private class FrameState(val engine: FilterEngine, val context: PageContext)

    @Volatile
    private var page: PageState? = null

    private val _blockedOnPage = MutableStateFlow(0)

    /** Requests, pop-ups and documents blocked since the current page started loading. */
    val blockedOnPage: StateFlow<Int> = _blockedOnPage.asStateFlow()

    /** Per-document contexts for frames, keyed by referrer URL (cleared on top-level navigation). */
    private val frameContexts: MutableMap<String, FrameState> =
        Collections.synchronizedMap(object : LinkedHashMap<String, FrameState>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FrameState>?) = size > FRAME_CACHE_SIZE
        })

    /**
     * Hosts of frame documents loaded in the current page. Only these may act
     * as a request's "page": a Referer from any other host belongs to a
     * subresource (e.g. a stylesheet loading fonts), whose requests are still
     * issued on behalf of the top document.
     */
    private val frameHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val popupHints = PopupHints()

    /** Hosts the user chose to open despite a document block (this tab, this session). */
    private val proceedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Secret embedded in block pages' "proceed" links so pages cannot forge them. */
    private val proceedToken: String = UUID.randomUUID().toString()

    private var usesDocumentStartScript = false

    /** Registers the bridge and the document-start script. Call once, on the UI thread, before loading. */
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun install(webView: WebView) {
        webView.addJavascriptInterface(Bridge(), assets.bridgeName)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, assets.contentScript, ALL_ORIGINS)
                usesDocumentStartScript = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Document-start scripts unavailable; falling back to page-start injection", e)
            }
        }
    }

    /** Fallback for WebView builds without document-start scripts: inject as early as the API allows. */
    fun onPageStarted(webView: WebView, url: String?) {
        if (url != null) {
            if (url != page?.url) _blockedOnPage.value = 0
            updatePage(url)
        }
        if (!usesDocumentStartScript && url != null && url.startsWith("http")) {
            webView.evaluateJavascript(assets.contentScript, null)
        }
    }

    /** Main-frame URL committed (including History API changes). */
    fun onUrlCommitted(url: String?) {
        if (url != null && url.startsWith("http")) updatePage(url)
    }

    /**
     * `shouldInterceptRequest` hook. Returns a replacement response for
     * blocked / redirected requests, or null to let the request through.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith("http")) return null
        val engine = repository.awaitEngine()
        val policies = policyStore.awaitCurrent()

        if (request.isForMainFrame) {
            _blockedOnPage.value = 0
            val state = updatePage(url, engine, policies)
            // Trusted site (or blocker off): the document and everything it loads pass untouched.
            if (!state.policy.filtering) return null
            if (!request.isRedirect) stats.recordPageFiltered()
            if (request.method != "GET") return null
            val host = request.url.host?.lowercase() ?: return null
            if (host !in proceedHosts) {
                val result = engine.matchDocument(url, request.method)
                if (result.shouldBlock) {
                    stats.recordBlockedPage()
                    _blockedOnPage.update { it + 1 }
                    return blockPage.render(url, result.filterText, proceedUrl(url))
                }
            }
            return engine.removeParams(url)?.let { cleanUrl ->
                stats.recordRemovedParams()
                replaceWithResponse(cleanUrl)
            }
        }

        val headers = request.requestHeaders
        val referer = headers.header("Referer")
        val documentUrl = referer?.takeIf { it.startsWith("http") } ?: page?.url
        val type = RequestTypeResolver.resolve(
            url = url,
            accept = headers.header("Accept"),
            hasRange = headers.header("Range") != null,
            method = request.method,
            hasOrigin = headers.header("Origin") != null,
            contentType = headers.header("Content-Type"),
            sameOrigin = documentUrl?.let { hostOf(it) == request.url.host?.lowercase() },
        )
        val top = currentPage(engine, policies)
        val sitePolicy = top?.policy ?: policies.forUrl(documentUrl)
        if (!sitePolicy.filtering) return null
        val pageContext = contextFor(top, referer, engine)
        val result = engine.matchRequest(url, type.primary, pageContext, request.method, type.alternatives)
        if (type.primary == RequestType.SUBDOCUMENT && !result.shouldBlock && frameHosts.size < MAX_FRAME_HOSTS) {
            request.url.host?.lowercase()?.let(frameHosts::add)
        }
        if (!result.shouldBlock) return null
        stats.recordBlockedRequest(request.url.host, type.primary, attributeHost = !isPrivate)
        _blockedOnPage.update { it + 1 }
        return when (result.decision) {
            MatchResult.Decision.REDIRECT -> redirectResponse(result.redirect) ?: blockedResponse()
            else -> blockedResponse()
        }
    }

    /**
     * `shouldOverrideUrlLoading` hook for page-initiated top-level
     * navigations. Returns true when the navigation must be cancelled:
     *  - it matches a `$popup` filter (sites also push popunders through the
     *    current tab), or
     *  - its target is blocked as a document and the user did not tap a link
     *    to it ([tappedLinkUrl]) — a script or embedded frame tried to send
     *    the page somewhere blocked; showing the block page would only
     *    replace the page the user is reading.
     */
    fun checkNavigation(request: WebResourceRequest, tappedLinkUrl: String?): Boolean {
        if (!request.isForMainFrame || request.isRedirect) return false
        val url = request.url.toString()
        if (!url.startsWith("http")) return false
        val opener = page ?: return false
        val policies = policyStore.current()
        val openerPolicy = policies.forUrl(opener.url)
        if (!openerPolicy.filtering) return false
        val engine = repository.currentEngine()
        val context = if (opener.engine === engine) opener.context else engine.pageContext(opener.url)
        val userLink = tappedLinkUrl == url
        if (openerPolicy.popupBlocking && engine.matchPopup(url, context, userLink).shouldBlock) {
            recordBlockedPopup()
            return true
        }
        if (userLink) return false
        val targetHost = url.toUri().host?.lowercase()
        if (targetHost in proceedHosts || !policies.forHost(targetHost).filtering) return false
        if (!engine.matchDocument(url).shouldBlock) return false
        recordBlockedPopup()
        return true
    }

    /**
     * Decides whether a new window for [targetUrl] may open (as a new tab).
     * Called on the UI thread from `onCreateWindow` once the popup's first
     * URL is known; [tappedLinkUrl] is the link under the user's last tap.
     */
    fun allowPopup(targetUrl: String, tappedLinkUrl: String?): Boolean {
        val hint = popupHints.take(targetUrl)
        val policies = policyStore.current()
        val topUrl = page?.url
        val openerPolicy = policies.forUrl(topUrl)
        // Pop-ups allowed on this site (or blocker off): any web page may open as a new tab.
        if (!openerPolicy.filtering || !openerPolicy.popupBlocking) return Hostnames.hostOf(targetUrl) != null
        val decision = PopupPolicy.decide(
            engine = repository.currentEngine(),
            topUrl = topUrl,
            target = targetUrl,
            hint = hint,
            tappedLinkUrl = tappedLinkUrl,
            filterTargetDocument = policies.forUrl(targetUrl).filtering,
        )
        Log.d(TAG, "Popup $targetUrl: $decision")
        if (!decision.allow && Hostnames.hostOf(targetUrl) != null) recordBlockedPopup()
        return decision.allow
    }

    private fun recordBlockedPopup() {
        stats.recordBlockedPopup()
        _blockedOnPage.update { it + 1 }
    }

    /**
     * Handles the block page's "proceed" link. Returns the URL to load when
     * [url] is a valid proceed request, else null.
     */
    fun consumeProceedRequest(url: String): String? {
        if (!url.startsWith(PROCEED_SCHEME)) return null
        val uri = url.toUri()
        if (uri.getQueryParameter("token") != proceedToken) return null
        val target = uri.getQueryParameter("url") ?: return null
        if (!target.startsWith("http://") && !target.startsWith("https://")) return null
        target.toUri().host?.lowercase()?.let(proceedHosts::add) ?: return null
        return target
    }

    private fun proceedUrl(url: String): String =
        "$PROCEED_SCHEME?token=$proceedToken&url=" + Uri.encode(url)

    private fun updatePage(
        url: String,
        engine: FilterEngine = repository.currentEngine(),
        policies: AdBlockPolicy = policyStore.current(),
    ): PageState {
        val current = page
        if (current != null && current.url == url && current.engine === engine && current.policies === policies) {
            return current
        }
        val sameDocumentHost = current != null && current.url.toUri().host == url.toUri().host
        if (!sameDocumentHost) {
            frameContexts.clear()
            frameHosts.clear()
        }
        return pageState(url, engine, policies).also { page = it }
    }

    private fun pageState(url: String, engine: FilterEngine, policies: AdBlockPolicy): PageState =
        PageState(url, engine, engine.pageContext(url), policies, policies.forUrl(url))

    /** The top document's state, rebuilt when the engine or the switches changed since it loaded. */
    private fun currentPage(engine: FilterEngine, policies: AdBlockPolicy): PageState? {
        val current = page ?: return null
        if (current.engine === engine && current.policies === policies) return current
        return pageState(current.url, engine, policies).also { page = it }
    }

    /** Context of the document that issued a subresource request. */
    private fun contextFor(top: PageState?, referer: String?, engine: FilterEngine): PageContext? {
        // Top-level `$document` allowlisting covers every frame of the page.
        if (top != null && top.context.isAllowlisted) return top.context
        if (referer.isNullOrEmpty() || !referer.startsWith("http")) return top?.context
        if (top != null && sameHost(referer, top.url)) return top.context
        if (top != null && hostOf(referer) !in frameHosts) return top.context
        frameContexts[referer]?.takeIf { it.engine === engine }?.let { return it.context }
        val state = FrameState(engine, engine.pageContext(referer))
        frameContexts[referer] = state
        return state.context
    }

    private fun sameHost(a: String, b: String): Boolean {
        val hostA = hostOf(a) ?: return false
        return hostA == hostOf(b)
    }

    private fun hostOf(url: String): String? {
        val start = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i
                break
            }
        }
        return url.substring(start, end).lowercase()
    }

    private fun redirectResponse(name: String?): WebResourceResponse? {
        val body = assets.redirectResource(name ?: return null) ?: return null
        return WebResourceResponse(
            body.mimeType, if (body.mimeType.startsWith("text/") || body.mimeType.endsWith("javascript")) "utf-8" else null,
            200, "OK", CORS_HEADERS, ByteArrayInputStream(body.bytes),
        )
    }

    /**
     * A document that immediately replaces itself (no extra history entry)
     * with [cleanUrl]. `no-referrer` keeps the stripped parameters from
     * leaking to the destination through the Referer header.
     */
    private fun replaceWithResponse(cleanUrl: String): WebResourceResponse {
        val html = "<!DOCTYPE html><meta name=\"referrer\" content=\"no-referrer\">" +
            "<script>location.replace(" + JSONObject.quote(cleanUrl) + ")</script>"
        return WebResourceResponse(
            "text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", CORS_HEADERS, ByteArrayInputStream(EMPTY))

    /** JavaScript interface used by the content script; called on WebView's JavaBridge thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun cosmetics(frameUrl: String?, topUrl: String?): String {
            if (frameUrl.isNullOrEmpty()) return EMPTY_PAYLOAD
            val top = topUrl?.takeIf { it.isNotEmpty() }
            val policy = policyStore.current().forUrl(top ?: frameUrl)
            if (!policy.filtering) return EMPTY_PAYLOAD
            return try {
                repository.currentEngine().cosmeticPayload(frameUrl, top, policy.cosmeticFiltering)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Cosmetic payload failed", e)
                EMPTY_PAYLOAD
            }
        }

        /** Records which frame is about to open a window, and how (see [PopupHint.Kind]). */
        @JavascriptInterface
        fun popup(frameUrl: String?, targetUrl: String?, kind: String?) {
            if (frameUrl.isNullOrEmpty()) return
            popupHints.record(frameUrl, targetUrl.orEmpty().take(MAX_URL_LENGTH), PopupHint.Kind.fromWire(kind))
        }

        @JavascriptInterface
        fun genericCss(frameUrl: String?, topUrl: String?, tokens: String?): String {
            if (frameUrl.isNullOrEmpty() || tokens.isNullOrEmpty()) return ""
            val top = topUrl?.takeIf { it.isNotEmpty() }
            val policy = policyStore.current().forUrl(top ?: frameUrl)
            if (!policy.filtering || !policy.cosmeticFiltering) return ""
            return try {
                val list = tokens.split(' ').filter { it.length > 1 }.take(MAX_TOKENS_PER_CALL)
                repository.currentEngine().genericCss(frameUrl, top, list)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Generic CSS failed", e)
                ""
            }
        }
    }

    companion object {
        private const val TAG = "ContentBlocker"
        private const val FRAME_CACHE_SIZE = 16
        private const val MAX_FRAME_HOSTS = 256
        private const val MAX_TOKENS_PER_CALL = 2048
        private const val MAX_URL_LENGTH = 8192
        /** Scheme of the block page's action links, handled in `shouldOverrideUrlLoading`. */
        const val ACTION_SCHEME = "nexa-adblock"
        private const val PROCEED_SCHEME = "$ACTION_SCHEME://proceed"
        const val BACK_URL = "$ACTION_SCHEME://back"
        private val ALL_ORIGINS = setOf("*")
        private val EMPTY = ByteArray(0)
        private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        private const val EMPTY_PAYLOAD = "{\"css\":\"\",\"procedural\":[],\"generic\":false,\"scriptlets\":[]}"

        private fun Map<String, String>.header(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
