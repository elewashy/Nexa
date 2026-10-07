package com.elewashy.nexa.feature.browser.presentation.webview

import android.net.http.SslError
import android.webkit.WebViewClient
import com.elewashy.nexa.feature.browser.domain.model.CertificateProblem
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewErrorMapperTest {

    @Test
    fun `cancelled navigations are not errors`() {
        assertNull(map(WebViewClient.ERROR_UNKNOWN, "net::ERR_ABORTED"))
    }

    @Test
    fun `chromium net error is the primary signal`() {
        val error = map(WebViewClient.ERROR_UNKNOWN, "net::ERR_NAME_NOT_RESOLVED")!!
        assertEquals(PageLoadErrorType.HostNotFound, error.type)
        assertEquals("ERR_NAME_NOT_RESOLVED", error.errorCode)
    }

    @Test
    fun `net errors map to the failure Chrome explains`() {
        val expected = mapOf(
            "net::ERR_CONNECTION_REFUSED" to PageLoadErrorType.ConnectionRefused,
            "net::ERR_CONNECTION_RESET" to PageLoadErrorType.ConnectionInterrupted,
            "net::ERR_NETWORK_CHANGED" to PageLoadErrorType.ConnectionInterrupted,
            "net::ERR_CONNECTION_TIMED_OUT" to PageLoadErrorType.TimedOut,
            "net::ERR_EMPTY_RESPONSE" to PageLoadErrorType.InvalidResponse,
            "net::ERR_TOO_MANY_REDIRECTS" to PageLoadErrorType.TooManyRedirects,
            "net::ERR_SSL_PROTOCOL_ERROR" to PageLoadErrorType.SecureConnectionFailed,
            "net::ERR_PROXY_CONNECTION_FAILED" to PageLoadErrorType.ProxyFailed,
            "net::ERR_UNKNOWN_URL_SCHEME" to PageLoadErrorType.UnsupportedAddress,
            "net::ERR_FILE_NOT_FOUND" to PageLoadErrorType.FileNotFound,
            "net::ERR_CACHE_MISS" to PageLoadErrorType.FormResubmission,
            "net::ERR_CLEARTEXT_NOT_PERMITTED" to PageLoadErrorType.AccessBlocked,
            "net::ERR_QUIC_PROTOCOL_ERROR" to PageLoadErrorType.Generic,
        )
        expected.forEach { (description, type) ->
            assertEquals(description, type, map(WebViewClient.ERROR_UNKNOWN, description)!!.type)
        }
    }

    @Test
    fun `webview code is the fallback without a net error`() {
        val error = map(WebViewClient.ERROR_TIMEOUT, "The connection timed out")!!
        assertEquals(PageLoadErrorType.TimedOut, error.type)
        assertEquals("ERR_TIMED_OUT", error.errorCode)
    }

    @Test
    fun `explicit disconnection is offline`() {
        val error = map(WebViewClient.ERROR_HOST_LOOKUP, "net::ERR_INTERNET_DISCONNECTED")!!
        assertEquals(PageLoadErrorType.NoInternet, error.type)
    }

    @Test
    fun `connection failures without any network are offline`() {
        val error = map(WebViewClient.ERROR_HOST_LOOKUP, "net::ERR_NAME_NOT_RESOLVED", hasNetwork = false)!!
        assertEquals(PageLoadErrorType.NoInternet, error.type)
        assertEquals("ERR_INTERNET_DISCONNECTED", error.errorCode)
    }

    @Test
    fun `non connectivity failures keep their cause without network`() {
        val error = map(WebViewClient.ERROR_UNKNOWN, "net::ERR_TOO_MANY_REDIRECTS", hasNetwork = false)!!
        assertEquals(PageLoadErrorType.TooManyRedirects, error.type)
    }

    @Test
    fun `safe browsing blocks are unsafe sites`() {
        val error = map(WebViewClient.ERROR_UNSAFE_RESOURCE, "net::ERR_BLOCKED_BY_CLIENT")!!
        assertEquals(PageLoadErrorType.UnsafeSite, error.type)
    }

    @Test
    fun `certificate net errors carry their problem and chrome prefix`() {
        val error = map(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, "net::ERR_CERT_DATE_INVALID")!!
        assertEquals(PageLoadErrorType.InsecureConnection, error.type)
        assertEquals(CertificateProblem.DateInvalid, error.certificateProblem)
        assertEquals("NET::ERR_CERT_DATE_INVALID", error.errorCode)
    }

    @Test
    fun `ssl errors map to the certificate problem`() {
        val expected = mapOf(
            SslError.SSL_EXPIRED to CertificateProblem.DateInvalid,
            SslError.SSL_NOTYETVALID to CertificateProblem.DateInvalid,
            SslError.SSL_IDMISMATCH to CertificateProblem.NameMismatch,
            SslError.SSL_UNTRUSTED to CertificateProblem.Untrusted,
            SslError.SSL_INVALID to CertificateProblem.Invalid,
        )
        expected.forEach { (primaryError, problem) ->
            val error = WebViewErrorMapper.fromSslError(URL, primaryError, certificate = null)
            assertEquals(PageLoadErrorType.InsecureConnection, error.type)
            assertEquals(problem, error.certificateProblem)
            assertEquals(true, error.errorCode.startsWith("NET::ERR_CERT_"))
        }
    }

    @Test
    fun `host is derived from the url`() {
        assertEquals("example.com", map(WebViewClient.ERROR_UNKNOWN, "net::ERR_FAILED")!!.host)
    }

    private fun map(code: Int, description: String, hasNetwork: Boolean = true) =
        WebViewErrorMapper.fromNetworkError(URL, code, description, hasNetwork)

    private companion object {
        const val URL = "https://example.com/path?q=1"
    }
}
