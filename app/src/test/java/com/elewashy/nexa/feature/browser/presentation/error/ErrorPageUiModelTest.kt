package com.elewashy.nexa.feature.browser.presentation.error

import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.CertificateProblem
import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPageUiModelTest {

    @Test
    fun `every failure has a page with a way forward`() {
        PageLoadErrorType.entries.forEach { type ->
            listOf(true, false).forEach { canGoBack ->
                val model = error(type).toErrorPageUiModel(canGoBack)
                assertNotEquals(0, model.title)
                assertNotEquals(0, model.message)
                assertNotEquals(model.primaryAction, model.secondaryAction)
                if (!canGoBack) {
                    assertTrue(
                        "$type offers Go back without history",
                        model.primaryAction != ErrorPageAction.GoBack &&
                            model.secondaryAction != ErrorPageAction.GoBack,
                    )
                }
            }
        }
    }

    @Test
    fun `offline page offers reload and the connectivity settings`() {
        val model = error(PageLoadErrorType.NoInternet).toErrorPageUiModel(canGoBack = true)
        assertEquals(R.string.error_page_title_offline, model.title)
        assertEquals(ErrorPageAction.Reload, model.primaryAction)
        assertEquals(ErrorPageAction.NetworkSettings, model.secondaryAction)
    }

    @Test
    fun `unreachable site names the host and offers to leave`() {
        val back = error(PageLoadErrorType.HostNotFound).toErrorPageUiModel(canGoBack = true)
        assertEquals(R.string.error_page_title_unreachable, back.title)
        assertTrue(back.messageNamesHost)
        assertEquals(ErrorPageAction.Reload, back.primaryAction)
        assertEquals(ErrorPageAction.GoBack, back.secondaryAction)

        val home = error(PageLoadErrorType.HostNotFound).toErrorPageUiModel(canGoBack = false)
        assertEquals(ErrorPageAction.GoHome, home.secondaryAction)
    }

    @Test
    fun `security warnings steer away from the site and never retry`() {
        listOf(PageLoadErrorType.InsecureConnection, PageLoadErrorType.UnsafeSite).forEach { type ->
            val model = error(type).toErrorPageUiModel(canGoBack = true)
            assertTrue(model.isSecurityWarning)
            assertEquals(ErrorPageAction.BackToSafety, model.primaryAction)
            assertNull(model.secondaryAction)
        }
    }

    @Test
    fun `certificate problems are explained`() {
        val expected = mapOf(
            CertificateProblem.DateInvalid to R.string.error_page_certificate_date,
            CertificateProblem.NameMismatch to R.string.error_page_certificate_name,
            CertificateProblem.Untrusted to R.string.error_page_certificate_untrusted,
            CertificateProblem.Invalid to R.string.error_page_certificate_invalid,
        )
        expected.forEach { (problem, reason) ->
            val model = error(PageLoadErrorType.InsecureConnection)
                .copy(certificateProblem = problem)
                .toErrorPageUiModel(canGoBack = true)
            assertEquals(reason, model.certificateReason)
        }
        val dateModel = error(PageLoadErrorType.InsecureConnection)
            .copy(certificateProblem = CertificateProblem.DateInvalid)
            .toErrorPageUiModel(canGoBack = true)
        assertEquals(listOf(R.string.error_page_suggestion_clock), dateModel.suggestions)
    }

    @Test
    fun `form resubmission asks before resending`() {
        val model = error(PageLoadErrorType.FormResubmission).toErrorPageUiModel(canGoBack = true)
        assertEquals(R.string.error_page_title_resubmission, model.title)
        assertEquals(ErrorPageAction.Resend, model.primaryAction)
    }

    private fun error(type: PageLoadErrorType) =
        PageLoadError(url = "https://example.com/", type = type, errorCode = "ERR_TEST")
}
