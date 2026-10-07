package com.elewashy.nexa.feature.browser.domain.model

import java.net.URI

/**
 * A main-frame navigation that failed and left its tab on an error page.
 *
 * Only navigations the WebView actually committed as an error page become a [PageLoadError]:
 * subresource failures, cancelled navigations and speculative (prefetch) requests never do.
 */
data class PageLoadError(
    /** Address the user tried to open. */
    val url: String,
    val type: PageLoadErrorType,
    /**
     * Chromium's name for the failure ("ERR_NAME_NOT_RESOLVED", "NET::ERR_CERT_DATE_INVALID"),
     * shown verbatim like Chrome's error pages do, so it can be searched or reported.
     */
    val errorCode: String,
    /** What is wrong with the server's certificate, for [PageLoadErrorType.InsecureConnection]. */
    val certificateProblem: CertificateProblem? = null,
    /** Certificate presented by the server, for [PageLoadErrorType.InsecureConnection]. */
    val certificate: CertificateDetails? = null,
) {
    /** Host of [url] ("example.com"), or [url] itself when it has none. */
    val host: String
        get() = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}

/** Why a page could not be loaded; drives the explanation and the actions offered. */
enum class PageLoadErrorType {
    /** The device has no network connection. */
    NoInternet,

    /** The server's address could not be resolved (DNS). */
    HostNotFound,

    /** The server refused or could not accept the connection. */
    ConnectionRefused,

    /** The connection was reset, closed or interrupted (including network changes). */
    ConnectionInterrupted,

    /** The server took too long to respond. */
    TimedOut,

    /** The server answered with no data or an invalid response. */
    InvalidResponse,

    /** The page redirects endlessly. */
    TooManyRedirects,

    /** The server's certificate is not valid for this site; the connection was refused. */
    InsecureConnection,

    /** A secure (TLS) connection could not be negotiated. */
    SecureConnectionFailed,

    /** The proxy server failed or requires authentication. */
    ProxyFailed,

    /** The address is malformed or uses a scheme the browser cannot open. */
    UnsupportedAddress,

    /** The requested file does not exist. */
    FileNotFound,

    /** Revisiting a page that was the result of a form submission (ERR_CACHE_MISS). */
    FormResubmission,

    /** The request was blocked by policy (cleartext not permitted, blocked by response, …). */
    AccessBlocked,

    /** Google Safe Browsing stopped the load. */
    UnsafeSite,

    /** Any other failure. */
    Generic,
    ;

    /** Failures where the safe action is to leave the site rather than retry. */
    val isSecurityRisk: Boolean
        get() = this == InsecureConnection || this == UnsafeSite
}

/** Why a server certificate was rejected, in the categories Chrome explains to users. */
enum class CertificateProblem {
    /** Expired, or not valid yet (often a wrong device clock). */
    DateInvalid,

    /** Issued for a different site. */
    NameMismatch,

    /** Not issued by an authority the device trusts (self-signed, private CA). */
    Untrusted,

    /** Revoked, weak, or otherwise invalid. */
    Invalid,
}

/** The parts of a server certificate that help the user judge a certificate error. */
data class CertificateDetails(
    val issuedTo: String?,
    val issuedBy: String?,
    val validFromMillis: Long?,
    val validUntilMillis: Long?,
)
