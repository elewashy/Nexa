package com.elewashy.nexa.feature.browser.presentation.webview

import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorPageVisibilityTest {

    private val published = mutableListOf<PageLoadError?>()
    private val visibility = ErrorPageVisibility { published += it }

    private val error = PageLoadError("https://down.example/", PageLoadErrorType.HostNotFound, "ERR_NAME_NOT_RESOLVED")

    @Test
    fun `an error page is shown as soon as it is attributed`() {
        visibility.show(error)

        assertEquals(listOf<PageLoadError?>(error), published)
    }

    @Test
    fun `a new document keeps the error page up until it paints`() {
        visibility.show(error)

        visibility.onDocumentStarted()
        visibility.onCommitted("https://other.example/", documentStarted = true)
        assertEquals(listOf<PageLoadError?>(error), published)

        visibility.onDocumentDisplayed()
        assertEquals(listOf(error, null), published)
    }

    @Test
    fun `a retry that succeeds for the same address clears the error page once it paints`() {
        visibility.show(error)

        visibility.onDocumentStarted()
        visibility.onCommitted(error.url, documentStarted = true)
        assertEquals(listOf<PageLoadError?>(error), published)

        visibility.onDocumentDisplayed()
        assertEquals(listOf(error, null), published)
    }

    @Test
    fun `finishing the load clears the error page when no visible commit was reported`() {
        visibility.show(error)
        visibility.onDocumentStarted()

        // onPageFinished stands in for a missing onPageCommitVisible; a later one changes nothing.
        visibility.onDocumentDisplayed()
        visibility.onDocumentDisplayed()

        assertEquals(listOf(error, null), published)
    }

    @Test
    fun `a page restored without a document start replaces the error page at once`() {
        visibility.show(error)

        visibility.onCommitted("https://previous.example/", documentStarted = false)

        assertEquals(listOf(error, null), published)
    }

    @Test
    fun `a commit of the error page itself or of its fragment keeps it`() {
        visibility.show(error)

        visibility.onCommitted(error.url, documentStarted = false)
        visibility.onCommitted(error.url + "#section", documentStarted = false)

        assertEquals(listOf<PageLoadError?>(error), published)
    }

    @Test
    fun `a failure of the next navigation replaces the error page without clearing it in between`() {
        visibility.show(error)
        val next = PageLoadError("https://next.example/", PageLoadErrorType.TimedOut, "ERR_TIMED_OUT")

        visibility.onDocumentStarted()
        visibility.show(next)
        // The error document of the new failure never clears it.
        visibility.onDocumentDisplayed()

        assertEquals(listOf(error, next), published)
    }

    @Test
    fun `a page load clears an error this client never showed`() {
        // The tab's error can outlive the client (WebView replaced after a renderer crash).
        visibility.onDocumentStarted()
        visibility.onDocumentDisplayed()

        assertEquals(listOf<PageLoadError?>(null), published)
    }

    @Test
    fun `display signals without a started document change nothing`() {
        visibility.show(error)

        visibility.onDocumentDisplayed()

        assertEquals(listOf<PageLoadError?>(error), published)
    }
}
