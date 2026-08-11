package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.USER_AGENT_DESKTOP
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.USER_AGENT_MOBILE
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unrolls redirect-based share links into their canonical URLs before
 * platform detection and extraction.
 *
 * Some platforms share links that only 302 to the actual post page, e.g.
 * Threads `/share/<code>/` links or Twitter `t.co` short links. Extractors
 * that parse post IDs out of the URL cannot work with those, so the redirect
 * chain is followed first and the final URL is used when it is safe to do so.
 *
 * Resolution is skipped entirely for links that are already canonical, so the
 * common path costs zero network requests.
 */
@Singleton
internal class ShareLinkResolver @Inject constructor(
    httpClientProvider: HttpClientProvider
) {

    private val client: OkHttpClient = httpClientProvider.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Hard cap over the whole probe sequence (redirect chains included).
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Returns the canonical URL for [url] by following redirects, or [url]
     * itself when resolution is unnecessary, fails, or lands somewhere
     * untrusted.
     */
    suspend fun resolve(url: String): String {
        if (!needsResolution(url)) return url

        val finalUrl = finalUrlOrNull(url) ?: return url
        if (!isUsable(original = url, resolved = finalUrl)) return url

        Log.d(TAG, "Resolved share link: $url -> $finalUrl")
        return finalUrl
    }

    /**
     * Only links that cannot be processed as-is are resolved:
     * - Unknown hosts may be short links (t.co, bit.ly, ...) pointing at a
     *   supported platform.
     * - Threads `/share/<code>/` links are redirects to `/post/<id>` pages.
     * Every other platform receives canonical share links, and extractors
     * that fetch pages follow redirects themselves.
     */
    private fun needsResolution(url: String): Boolean = needsResolution(url, SharePlatformDetector.detect(url))

    /**
     * Only links that cannot be processed as-is are resolved:
     * - Unknown hosts may be short links (t.co, bit.ly, ...) pointing at a
     *   supported platform.
     * - Threads `/share/<code>/` links are redirects to `/post/<id>` pages.
     * Every other platform receives canonical share links, and extractors
     * that fetch pages follow redirects themselves.
     *
     * [platform] is a defaulted parameter (detected at runtime) so the gate
     * is unit-testable on the JVM, where `android.net.Uri` is unavailable.
     */
    internal fun needsResolution(url: String, platform: SharePlatform): Boolean = when (platform) {
        SharePlatform.VIDEO -> isProbeCandidate(url)
        SharePlatform.THREADS -> !THREADS_POST_RE.containsMatchIn(url)
        else -> false
    }

    /**
     * Rejects unknown-host URLs that can never be a short link to a supported
     * platform, so garbage input never fans out into network probes:
     * oversized URLs, IP-literal hosts, and hosts without a dot.
     */
    internal fun isProbeCandidate(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        val host = url.toHttpUrlOrNull()?.host ?: return false
        if (!host.contains('.')) return false
        if (host.contains(':')) return false // IPv6 literal
        if (host.all { it.isDigit() || it == '.' }) return false // IPv4 literal
        return true
    }

    /**
     * Probes sequentially, cheapest first, and stops as soon as an answer is
     * found. Threads `/share/` links only redirect generic user agents, so
     * that probe goes first and resolves the common redirect case with a
     * single request. GET is used only for servers that rejected HEAD.
     */
    private fun finalUrlOrNull(url: String): String? {
        var sawHeadFailure = false

        for (userAgent in USER_AGENTS) {
            when (val probe = followWithHead(url, userAgent)) {
                is Probe.Final -> {
                    if (probe.url != url) return probe.url
                    // Served directly to the most redirect-prone user agent:
                    // short links redirect regardless of UA, so nothing here
                    // is a redirect link.
                    if (userAgent == USER_AGENT_GENERIC) return null
                }
                Probe.Failed -> sawHeadFailure = true
            }
        }

        if (!sawHeadFailure) return null

        for (userAgent in USER_AGENTS) {
            followWithGet(url, userAgent)?.takeIf { it != url }?.let { return it }
        }
        return null
    }

    /**
     * A resolved URL is used when it still maps to a supported platform:
     * - Unknown hosts (short links like t.co) may resolve to any supported platform.
     * - Known platforms must resolve within the same platform, guarding against
     *   unexpected cross-domain redirects (login walls, consent pages, etc.).
     */
    private fun isUsable(original: String, resolved: String): Boolean {
        val resolvedPlatform = SharePlatformDetector.detect(resolved)
        if (resolvedPlatform == SharePlatform.VIDEO) return false

        val originalPlatform = SharePlatformDetector.detect(original)
        return originalPlatform == SharePlatform.VIDEO || resolvedPlatform == originalPlatform
    }

    private sealed interface Probe {
        data class Final(val url: String) : Probe
        data object Failed : Probe
    }

    private fun followWithHead(url: String, userAgent: String): Probe = runCatching {
        execute(url, userAgent) { head() }.use { response ->
            if (response.isSuccessful) {
                Probe.Final(response.request.url.toString())
            } else {
                Probe.Failed
            }
        }
    }.getOrElse { e ->
        Log.w(TAG, "HEAD resolution failed for $url: ${e.message}")
        Probe.Failed
    }

    /**
     * Fallback for servers that reject HEAD. The body is never read; only the
     * final request URL after the redirect chain is needed.
     */
    private fun followWithGet(url: String, userAgent: String): String? = runCatching {
        execute(url, userAgent) { }.use { response ->
            if (response.isSuccessful) response.request.url.toString() else null
        }
    }.getOrElse { e ->
        Log.w(TAG, "GET resolution failed for $url: ${e.message}")
        null
    }

    private inline fun execute(url: String, userAgent: String, configure: Request.Builder.() -> Unit) =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .apply(configure)
            .build()
            .let(client::newCall)
            .execute()

    private companion object {
        const val TAG = "ShareLinkResolver"
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 8L
        const val CALL_TIMEOUT_SECONDS = 12L
        const val MAX_URL_LENGTH = 2048

        const val USER_AGENT_GENERIC = "Mozilla/5.0"
        val USER_AGENTS = listOf(USER_AGENT_GENERIC, USER_AGENT_DESKTOP, USER_AGENT_MOBILE)

        /** Canonical Threads post path; mirrors ThreadsVideoExtractor's validation. */
        val THREADS_POST_RE = Regex("/post/([^/?]+)")
    }
}
