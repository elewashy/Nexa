package com.elewashy.nexa.feature.browser.presentation.webview

import android.net.http.SslError
import android.webkit.WebViewClient
import com.elewashy.nexa.feature.browser.domain.model.CertificateDetails
import com.elewashy.nexa.feature.browser.domain.model.CertificateProblem
import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType

/**
 * Translates WebView error callbacks into [PageLoadError]s.
 *
 * WebView reports a coarse `WebViewClient.ERROR_*` code plus Chromium's own net error name in the
 * description ("net::ERR_NAME_NOT_RESOLVED"). The net error is far more precise, so it is the
 * primary signal and the coarse code is only a fallback — the same names Chrome prints on its
 * error pages.
 */
internal object WebViewErrorMapper {

    private val NET_ERROR = Regex("""\b(ERR_[A-Z0-9_]+)\b""")

    private const val ERR_ABORTED = "ERR_ABORTED"
    private const val ERR_INTERNET_DISCONNECTED = "ERR_INTERNET_DISCONNECTED"

    /** Chromium's net error name in a WebView error description, if any. */
    fun netErrorName(description: CharSequence?): String? =
        description?.let { NET_ERROR.find(it)?.groupValues?.get(1) }

    /**
     * Cancelled navigations (a newer navigation replaced them, a download took over, a 204
     * response) are reported as errors but never leave the user on an error page.
     */
    fun isBenign(description: CharSequence?): Boolean = netErrorName(description) == ERR_ABORTED

    /**
     * Maps a main-frame `onReceivedError`. [hasNetwork] is whether the device has any active
     * network: without one, every connection failure is reported as [PageLoadErrorType.NoInternet].
     * Returns null for [isBenign] errors.
     */
    fun fromNetworkError(
        url: String,
        webViewErrorCode: Int,
        description: CharSequence?,
        hasNetwork: Boolean,
    ): PageLoadError? {
        if (isBenign(description)) return null
        val netError = netErrorName(description)
        // Safe Browsing is the one case where WebView's own code is more specific than the net
        // error it carries (a generic "blocked" error).
        val type = if (webViewErrorCode == WebViewClient.ERROR_UNSAFE_RESOURCE) {
            PageLoadErrorType.UnsafeSite
        } else {
            netError?.let(::typeOfNetError) ?: typeOfWebViewCode(webViewErrorCode)
        }
        if (type == PageLoadErrorType.NoInternet || (!hasNetwork && type in CONNECTIVITY_FAILURES)) {
            return PageLoadError(url, PageLoadErrorType.NoInternet, ERR_INTERNET_DISCONNECTED)
        }
        return PageLoadError(
            url = url,
            type = type,
            // Chrome prints certificate errors with their "NET::" prefix.
            errorCode = when {
                netError == null -> fallbackNetError(webViewErrorCode)
                type == PageLoadErrorType.InsecureConnection -> "NET::$netError"
                else -> netError
            },
            certificateProblem = netError
                ?.takeIf { type == PageLoadErrorType.InsecureConnection }
                ?.let(::certificateProblemOfNetError),
        )
    }

    /** Maps `onReceivedSslError`; the navigation is always cancelled, never allowed to proceed. */
    fun fromSslError(url: String, primaryError: Int, certificate: CertificateDetails?): PageLoadError {
        val netError = certificateNetError(primaryError)
        return PageLoadError(
            url = url,
            type = PageLoadErrorType.InsecureConnection,
            errorCode = "NET::$netError",
            certificateProblem = certificateProblemOfNetError(netError),
            certificate = certificate,
        )
    }

    /** The user-facing category of a Chromium `ERR_CERT_*` error. */
    fun certificateProblemOfNetError(name: String): CertificateProblem = when (name) {
        "ERR_CERT_DATE_INVALID" -> CertificateProblem.DateInvalid
        "ERR_CERT_COMMON_NAME_INVALID" -> CertificateProblem.NameMismatch
        "ERR_CERT_AUTHORITY_INVALID" -> CertificateProblem.Untrusted
        else -> CertificateProblem.Invalid
    }

    /** Chromium's certificate error name for an [SslError] primary error. */
    fun certificateNetError(primaryError: Int): String = when (primaryError) {
        SslError.SSL_NOTYETVALID,
        SslError.SSL_EXPIRED,
        SslError.SSL_DATE_INVALID -> "ERR_CERT_DATE_INVALID"
        SslError.SSL_IDMISMATCH -> "ERR_CERT_COMMON_NAME_INVALID"
        SslError.SSL_UNTRUSTED -> "ERR_CERT_AUTHORITY_INVALID"
        else -> "ERR_CERT_INVALID"
    }

    private fun typeOfNetError(name: String): PageLoadErrorType? = when {
        name == ERR_INTERNET_DISCONNECTED -> PageLoadErrorType.NoInternet
        name == "ERR_NAME_NOT_RESOLVED" ||
            name == "ERR_NAME_RESOLUTION_FAILED" ||
            name.startsWith("ERR_DNS_") -> PageLoadErrorType.HostNotFound
        name == "ERR_CONNECTION_REFUSED" -> PageLoadErrorType.ConnectionRefused
        name == "ERR_CONNECTION_RESET" ||
            name == "ERR_CONNECTION_CLOSED" ||
            name == "ERR_CONNECTION_ABORTED" ||
            name == "ERR_CONNECTION_FAILED" ||
            name == "ERR_NETWORK_CHANGED" ||
            name == "ERR_NETWORK_IO_SUSPENDED" ||
            name == "ERR_ADDRESS_UNREACHABLE" -> PageLoadErrorType.ConnectionInterrupted
        name == "ERR_TIMED_OUT" || name == "ERR_CONNECTION_TIMED_OUT" -> PageLoadErrorType.TimedOut
        name == "ERR_EMPTY_RESPONSE" ||
            name == "ERR_INVALID_RESPONSE" ||
            name == "ERR_INVALID_HTTP_RESPONSE" ||
            name == "ERR_HTTP_RESPONSE_CODE_FAILURE" ||
            name == "ERR_CONTENT_DECODING_FAILED" ||
            name.startsWith("ERR_RESPONSE_HEADERS_") -> PageLoadErrorType.InvalidResponse
        name == "ERR_TOO_MANY_REDIRECTS" -> PageLoadErrorType.TooManyRedirects
        name.startsWith("ERR_CERT_") -> PageLoadErrorType.InsecureConnection
        name.startsWith("ERR_SSL_") || name == "ERR_BAD_SSL_CLIENT_AUTH_CERT" ->
            PageLoadErrorType.SecureConnectionFailed
        name.startsWith("ERR_PROXY_") || name == "ERR_TUNNEL_CONNECTION_FAILED" ->
            PageLoadErrorType.ProxyFailed
        name == "ERR_UNKNOWN_URL_SCHEME" ||
            name == "ERR_DISALLOWED_URL_SCHEME" ||
            name == "ERR_INVALID_URL" -> PageLoadErrorType.UnsupportedAddress
        name == "ERR_FILE_NOT_FOUND" -> PageLoadErrorType.FileNotFound
        name == "ERR_CACHE_MISS" -> PageLoadErrorType.FormResubmission
        name == "ERR_CLEARTEXT_NOT_PERMITTED" ||
            name == "ERR_ACCESS_DENIED" ||
            name == "ERR_NETWORK_ACCESS_DENIED" ||
            name.startsWith("ERR_BLOCKED_BY_") -> PageLoadErrorType.AccessBlocked
        else -> null
    }

    private fun typeOfWebViewCode(code: Int): PageLoadErrorType = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP -> PageLoadErrorType.HostNotFound
        WebViewClient.ERROR_CONNECT -> PageLoadErrorType.ConnectionRefused
        WebViewClient.ERROR_IO -> PageLoadErrorType.ConnectionInterrupted
        WebViewClient.ERROR_TIMEOUT -> PageLoadErrorType.TimedOut
        WebViewClient.ERROR_REDIRECT_LOOP -> PageLoadErrorType.TooManyRedirects
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> PageLoadErrorType.SecureConnectionFailed
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> PageLoadErrorType.ProxyFailed
        WebViewClient.ERROR_UNSUPPORTED_SCHEME,
        WebViewClient.ERROR_BAD_URL -> PageLoadErrorType.UnsupportedAddress
        WebViewClient.ERROR_FILE,
        WebViewClient.ERROR_FILE_NOT_FOUND -> PageLoadErrorType.FileNotFound
        WebViewClient.ERROR_UNSAFE_RESOURCE -> PageLoadErrorType.UnsafeSite
        else -> PageLoadErrorType.Generic
    }

    /** Net error name for a WebView code whose description carried none. */
    private fun fallbackNetError(code: Int): String = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP -> "ERR_NAME_NOT_RESOLVED"
        WebViewClient.ERROR_CONNECT -> "ERR_CONNECTION_FAILED"
        WebViewClient.ERROR_IO -> "ERR_CONNECTION_RESET"
        WebViewClient.ERROR_TIMEOUT -> "ERR_TIMED_OUT"
        WebViewClient.ERROR_REDIRECT_LOOP -> "ERR_TOO_MANY_REDIRECTS"
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "ERR_SSL_PROTOCOL_ERROR"
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> "ERR_PROXY_AUTH_REQUESTED"
        WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "ERR_UNKNOWN_URL_SCHEME"
        WebViewClient.ERROR_BAD_URL -> "ERR_INVALID_URL"
        WebViewClient.ERROR_FILE,
        WebViewClient.ERROR_FILE_NOT_FOUND -> "ERR_FILE_NOT_FOUND"
        WebViewClient.ERROR_UNSAFE_RESOURCE -> "ERR_BLOCKED_BY_CLIENT"
        WebViewClient.ERROR_TOO_MANY_REQUESTS -> "ERR_TOO_MANY_REQUESTS"
        WebViewClient.ERROR_AUTHENTICATION,
        WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME -> "ERR_INVALID_AUTH_CREDENTIALS"
        else -> "ERR_FAILED"
    }

    /** Failures that, without any network, really mean "you are offline". */
    private val CONNECTIVITY_FAILURES = setOf(
        PageLoadErrorType.HostNotFound,
        PageLoadErrorType.ConnectionRefused,
        PageLoadErrorType.ConnectionInterrupted,
        PageLoadErrorType.TimedOut,
        PageLoadErrorType.ProxyFailed,
        PageLoadErrorType.Generic,
    )
}
