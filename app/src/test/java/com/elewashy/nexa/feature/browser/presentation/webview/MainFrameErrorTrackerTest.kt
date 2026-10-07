package com.elewashy.nexa.feature.browser.presentation.webview

import com.elewashy.nexa.feature.browser.domain.model.CertificateDetails
import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainFrameErrorTrackerTest {

    private val tracker = MainFrameErrorTracker()

    @Test
    fun `error reported before the error page loads surfaces on commit`() {
        // Current WebView order: onReceivedError, then the error document starts and commits.
        tracker.onNavigationRequested()
        tracker.onError(error(PAGE))

        assertTrue(tracker.onDocumentStarted(PAGE))
        assertEquals(error(PAGE), tracker.onCommitted(PAGE))
        assertNull(tracker.onPageFinished(PAGE))
    }

    @Test
    fun `error reported after the page started surfaces on commit or finish`() {
        // Older WebView order: onPageStarted, onReceivedError, commit, onPageFinished.
        tracker.onNavigationRequested()
        assertFalse(tracker.onDocumentStarted(PAGE))
        tracker.onError(error(PAGE))

        assertEquals(error(PAGE), tracker.onCommitted(PAGE))
    }

    @Test
    fun `error surfaces on finish when the commit came before the error`() {
        tracker.onNavigationRequested()
        tracker.onDocumentStarted(PAGE)
        assertNull(tracker.onCommitted(PAGE))
        tracker.onError(error(PAGE))

        assertEquals(error(PAGE), tracker.onPageFinished(PAGE))
    }

    @Test
    fun `speculative request failure never covers the page that loaded`() {
        // A prefetch of a link on the page fails while the page itself loads fine.
        tracker.onNavigationRequested()
        tracker.onDocumentStarted(PAGE)
        assertNull(tracker.onCommitted(PAGE))
        tracker.onError(error(OTHER_PAGE))

        assertNull(tracker.onPageFinished(PAGE))
        // ...and is gone afterwards: it cannot surface for a later same-URL commit either.
        tracker.onDocumentStarted(OTHER_PAGE)
        assertNull(tracker.onCommitted(OTHER_PAGE))
    }

    @Test
    fun `subresource certificate error from the page host never covers the page`() {
        tracker.onNavigationRequested()
        tracker.onDocumentStarted(PAGE)
        tracker.onCommitted(PAGE)
        tracker.onError(error("https://example.com/static/app.js", PageLoadErrorType.InsecureConnection))

        assertNull(tracker.onPageFinished(PAGE))
    }

    @Test
    fun `late error for the current URL is ignored by same-document commits`() {
        tracker.onNavigationRequested()
        tracker.onDocumentStarted(PAGE)
        tracker.onCommitted(PAGE)
        tracker.onPageFinished(PAGE)

        // The page is up; a stray error for its URL arrives, then the page uses the History API.
        tracker.onError(error(PAGE))
        assertNull(tracker.onCommitted(PAGE))
        assertNull(tracker.onCommitted("$PAGE#section"))
        assertNull(tracker.onPageFinished("$PAGE#section"))
    }

    @Test
    fun `a new navigation discards errors of the previous one`() {
        tracker.onError(error(PAGE))
        tracker.onNavigationRequested()

        assertFalse(tracker.onDocumentStarted(PAGE))
        assertNull(tracker.onCommitted(PAGE))
    }

    @Test
    fun `fragment finish keeps the error of the navigation in flight`() {
        tracker.onNavigationRequested()
        tracker.onError(error(OTHER_PAGE))
        // A fragment navigation on the old page finishes without a started document.
        assertNull(tracker.onPageFinished("$PAGE#top"))

        assertTrue(tracker.onDocumentStarted(OTHER_PAGE))
        assertEquals(error(OTHER_PAGE), tracker.onCommitted(OTHER_PAGE))
    }

    @Test
    fun `urls are matched without their fragment`() {
        tracker.onNavigationRequested()
        tracker.onError(error("$PAGE#intro"))

        assertTrue(tracker.onDocumentStarted(PAGE))
        assertEquals(error("$PAGE#intro"), tracker.onCommitted(PAGE))
    }

    @Test
    fun `certificate error is kept over the generic failure of the same request`() {
        val certificateError = error(PAGE, PageLoadErrorType.InsecureConnection).copy(
            certificate = CertificateDetails("example.com", "Example CA", null, null),
        )
        tracker.onNavigationRequested()
        tracker.onError(certificateError)
        tracker.onError(error(PAGE, PageLoadErrorType.Generic))

        tracker.onDocumentStarted(PAGE)
        assertEquals(certificateError, tracker.onCommitted(PAGE))
    }

    @Test
    fun `pending errors are bounded`() {
        val tracker = MainFrameErrorTracker(maxPending = 2)
        tracker.onError(error("https://a.example/"))
        tracker.onError(error("https://b.example/"))
        tracker.onError(error("https://c.example/"))

        assertFalse(tracker.hasPendingError("https://a.example/"))
        assertTrue(tracker.hasPendingError("https://b.example/"))
        assertTrue(tracker.hasPendingError("https://c.example/"))
    }

    private fun error(url: String, type: PageLoadErrorType = PageLoadErrorType.HostNotFound) =
        PageLoadError(url = url, type = type, errorCode = "ERR_TEST")

    private companion object {
        const val PAGE = "https://example.com/article"
        const val OTHER_PAGE = "https://example.com/next"
    }
}
