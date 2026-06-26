package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.links.ValidLinkRepository
import com.elewashy.nexa.feature.browser.data.regex.RegexPatterns
import com.elewashy.nexa.feature.browser.data.scripts.ScriptRepository
import com.elewashy.nexa.feature.browser.data.scripts.ScriptType
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView client for URL interception, ad blocking, script injection and page
 * lifecycle events.
 */
class NexaWebViewClient(
    appContext: Context,
    private val adBlockRepository: AdBlockRepository,
    private val validLinkRepository: ValidLinkRepository,
    private val scriptRepository: ScriptRepository,
    private val onPageStartedEvent: (url: String?, isImmersiveHost: Boolean) -> Unit = { _, _ -> },
    private val onPageFinishedEvent: () -> Unit = {},
    private val onNavigationConsumedEvent: () -> Unit = {},
    private val onUrlUpdatedEvent: (String?) -> Unit = {},
    private val pageStartedCallback: (WebView?, String?) -> Unit = { _, _ -> },
    private val pageFinishedCallback: (WebView?, String?) -> Unit = { _, _ -> },
    private val urlUpdatedCallback: (String?) -> Unit = {},
    private val mainFrameLoadErrorCallback: (Boolean) -> Unit = {},
) : WebViewClient() {

    private companion object {
        private const val TAG = "NexaWebViewClient"
        private const val TRACE = "URLTrace"

        private const val AD_HOSTS_CACHE_MAX_SIZE = 512

        private val IMMERSIVE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
        private val EMPTY_BYTES = ByteArray(0)

        private fun blockedResponse() = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(EMPTY_BYTES),
        )
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

        if (url.equals("about:blank", ignoreCase = true)) {
            onNavigationConsumedEvent()
            return true
        }

        val uri = Uri.parse(url)
        val host = uri.host

        if (host != null && isGloballyWhitelisted(host)) return false

        if (url.startsWith("aliexpress://", ignoreCase = true) || url.startsWith("intent://", ignoreCase = true)) {
            onNavigationConsumedEvent()
            return true
        }

        if (shouldBlockUrl(url, host)) {
            onNavigationConsumedEvent()
            return true
        }

        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val host = uri.host
        val scheme = uri.scheme

        if (scheme == "about") return super.shouldInterceptRequest(view, request)

        val isWhitelistedRequest = if (host == null) {
            false
        } else if (request.isForMainFrame) {
            isGloballyWhitelisted(host)
        } else {
            isWhitelistedForPage(host, resolvePageHost(request))
        }
        if (isWhitelistedRequest) return super.shouldInterceptRequest(view, request)

        if (host != null) {
            val norm = normalizeHost(host)
            if (safeHostsCache.contains(norm)) return super.shouldInterceptRequest(view, request)
            if (isHostCached(norm)) return blockedResponse()
            if (adBlockRepository.isAdHost(norm)) {
                if (adHostsCache.size < AD_HOSTS_CACHE_MAX_SIZE) adHostsCache.add(norm)
                return blockedResponse()
            }
        }

        val url = uri.toString()
        return try {
            if (combinedAdRegex.matches(url)) {
                blockedResponse()
            } else {
                if (host != null && safeHostsCache.size < 512) safeHostsCache.add(normalizeHost(host))
                super.shouldInterceptRequest(view, request)
            }
        } catch (_: Exception) {
            super.shouldInterceptRequest(view, request)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        mainFrameLoadErrorCallback(false)
        scriptRepository.inject(view, ScriptType.PRE_LOAD)
        currentPageHost = normalizeUrlHost(url)
        onPageStartedEvent(url, isImmersiveUrl(url))
        pageStartedCallback(view, url)
        urlUpdatedCallback(url)
        onUrlUpdatedEvent(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.requestLayout()
        scriptRepository.inject(view, ScriptType.POST_LOAD)
        onPageFinishedEvent()
        pageFinishedCallback(view, url)
        urlUpdatedCallback(url)
        onUrlUpdatedEvent(url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        currentPageHost = normalizeUrlHost(url)
        scriptRepository.inject(view, ScriptType.PRE_LOAD)
        urlUpdatedCallback(url)
        onUrlUpdatedEvent(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) mainFrameLoadErrorCallback(true)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) mainFrameLoadErrorCallback(true)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Log.w(TAG, "Cancelling navigation due to SSL error: ${error.url}")
        mainFrameLoadErrorCallback(true)
        handler.cancel()
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

    private fun normalizeUrlHost(url: String?): String? = try {
        if (url.isNullOrBlank()) null else Uri.parse(url).host?.trim('.')?.lowercase()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun resolvePageHost(request: WebResourceRequest): String? {
        request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value?.let {
            normalizeUrlHost(it)?.let { host -> return host }
        }
        request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value?.let {
            normalizeUrlHost(it)?.let { host -> return host }
        }
        return currentPageHost
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
            combinedAdRegex.matches(url)
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

    private fun isImmersiveUrl(url: String?): Boolean {
        val host = normalizeUrlHost(url) ?: return false
        return IMMERSIVE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

}
