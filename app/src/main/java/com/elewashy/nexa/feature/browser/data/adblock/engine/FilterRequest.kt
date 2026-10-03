package com.elewashy.nexa.feature.browser.data.adblock.engine

import java.util.Locale

/**
 * Everything a network filter needs to evaluate one request, computed once.
 *
 * @property rawUrl original URL (used by `$match-case` and regex filters).
 * @property url lowercase URL used by case-insensitive patterns.
 * @property hostStart index of the hostname inside [url].
 * @property hostEnd end index (exclusive) of the hostname inside [url].
 * @property type the request's [RequestType] — its most likely type, which
 *   blocking filters are matched against.
 * @property exceptionTypes [type] plus every other type the request might
 *   be. Exception filters match against this wider set, so when WebView
 *   cannot tell a request's type for sure, a type-specific exception still
 *   allows it while a type-specific block never over-applies.
 * @property method [Method] bit of the HTTP method.
 * @property pageContext context of the document that issued the request.
 */
class FilterRequest private constructor(
    val rawUrl: String,
    val url: String,
    val host: String,
    val hostStart: Int,
    val hostEnd: Int,
    val type: Int,
    val exceptionTypes: Int,
    val method: Int,
    val hostContext: HostContext,
    val pageContext: HostContext,
    val isThirdParty: Boolean,
) {
    companion object {
        /** Bound on URL length; pathological multi-megabyte data URLs never reach the matcher. */
        const val MAX_URL_LENGTH = 4096

        /**
         * Builds a request, or returns null for URLs without a network host
         * (data:, blob:, about:, …) that filters never apply to.
         */
        fun create(
            url: String,
            type: Int,
            pageHost: String?,
            method: String = "GET",
            resolver: RegistrableDomainResolver,
            pageContextOverride: HostContext? = null,
            alternativeTypes: Int = 0,
        ): FilterRequest? {
            val bounded = if (url.length > MAX_URL_LENGTH) url.substring(0, MAX_URL_LENGTH) else url
            val lower = bounded.lowercase(Locale.ROOT)
            // Index arithmetic below assumes both forms align; non-ASCII case
            // folding can change length, in which case the lowercase form wins.
            val rawUrl = if (lower.length == bounded.length) bounded else lower
            val schemeEnd = lower.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = lower.substring(0, schemeEnd)
            if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
            val host = Hostnames.hostOf(lower) ?: return null
            val hostStart = lower.indexOf(host, schemeEnd + 3)
            if (hostStart == -1) return null
            val hostContext = HostContext.of(host, resolver)
            val pageContext = pageContextOverride
                ?: pageHost?.takeIf { it.isNotEmpty() }?.let { HostContext.of(it, resolver) }
                ?: hostContext
            val thirdParty = isThirdParty(hostContext, pageContext)
            return FilterRequest(
                rawUrl = rawUrl,
                url = lower,
                host = host,
                hostStart = hostStart,
                hostEnd = hostStart + host.length,
                type = type,
                exceptionTypes = type or alternativeTypes,
                method = Method.fromName(method).takeIf { it != 0 } ?: Method.GET,
                hostContext = hostContext,
                pageContext = pageContext,
                isThirdParty = thirdParty,
            )
        }

        private fun isThirdParty(request: HostContext, page: HostContext): Boolean {
            if (page.host.isEmpty()) return false
            if (request.host == page.host) return false
            val requestDomain = request.registrableDomain ?: request.host
            val pageDomain = page.registrableDomain ?: page.host
            return requestDomain != pageDomain
        }
    }
}
