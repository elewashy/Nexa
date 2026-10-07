package com.elewashy.nexa.feature.browser.domain.model

import java.net.URI
import java.net.URISyntaxException

/**
 * The page opened by the Home action and for new tabs.
 *
 * Follows the selected [SearchEngine] by default, so changing the engine keeps the home page in
 * step; a [Custom] page stays fixed until the user changes it.
 */
sealed interface HomePage {

    /** The selected search engine's own home page. */
    data object SearchEngineHome : HomePage

    /** A user-chosen address; always a normalized http(s) URL (see [HomePageUrls.normalize]). */
    data class Custom(val url: String) : HomePage

    /** The address to open while [engine] is the selected search engine. */
    fun urlFor(engine: SearchEngine): String = when (this) {
        SearchEngineHome -> engine.homeUrl
        is Custom -> url
    }

    companion object {
        /**
         * Reads the persisted value: null means [SearchEngineHome]. A stored address that is no
         * longer a valid web address also falls back to it, so a bad value can never strand Home.
         */
        fun fromStoredValue(url: String?): HomePage =
            url?.let(HomePageUrls::normalize)?.let(::Custom) ?: SearchEngineHome
    }
}

/** Validation and normalization of home page addresses typed by the user. */
object HomePageUrls {

    private const val MAX_LENGTH = 2048

    /**
     * Returns [input] as an absolute http(s) URL, or null when it is not a web address.
     *
     * Like the address bar, a scheme-less entry ("example.com", "example.com/news") is treated as
     * https. Only http(s) is accepted: a home page loads automatically, so `javascript:`, `file:`,
     * `data:` or intent URLs must never be stored. The host must look like a real one (contain a
     * dot, or be `localhost`), so stray words are rejected instead of becoming "https://word/", and
     * embedded credentials are refused.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH || trimmed.any(Char::isWhitespace)) return null
        val hasScheme = SCHEME.containsMatchIn(trimmed)
        val candidate = if (hasScheme) trimmed else "https://$trimmed"
        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trimEnd('.')?.lowercase() ?: return null
        if (host.isEmpty() || (!host.contains('.') && host != "localhost")) return null
        if (host.startsWith('.') || host.contains("..")) return null
        // Credentials in a URL only serve to disguise the real host.
        if (uri.rawUserInfo != null) return null
        // A bare origin gets the trailing slash a browser shows for it.
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return buildString {
            append(scheme).append("://")
            append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    /** "scheme:" at the start, but not "host:port" (a port is all digits). */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d+(/|$))")
}
