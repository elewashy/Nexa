package com.elewashy.nexa.feature.browser.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.CertificateDetails
import com.elewashy.nexa.feature.browser.domain.model.CertificateProblem
import com.elewashy.nexa.feature.browser.domain.model.HomePage
import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.browser.presentation.error.BrowserErrorPage
import com.elewashy.nexa.feature.browser.presentation.error.BrowserErrorPagePreview
import com.elewashy.nexa.feature.browser.presentation.error.ErrorPageAction
import com.elewashy.nexa.feature.settings.presentation.settings.HomePageSettingsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserRedesignScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    // ── Error page ────────────────────────────────────────────────────

    @Test
    fun `error page explains the failure and offers reload and go back`() {
        val actions = mutableListOf<ErrorPageAction>()
        compose.setContent {
            MaterialTheme {
                BrowserErrorPage(
                    error = PageLoadError("https://missing.example/a", PageLoadErrorType.HostNotFound, "ERR_NAME_NOT_RESOLVED"),
                    canGoBack = true,
                    isReloading = false,
                    onAction = { actions += it },
                )
            }
        }

        compose.onNodeWithText(string(R.string.error_page_title_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.error_page_message_host_not_found, "missing.example")).assertIsDisplayed()
        compose.onNodeWithText("ERR_NAME_NOT_RESOLVED").assertIsDisplayed()

        compose.onNodeWithText(string(R.string.error_page_reload)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.error_page_go_back)).performScrollTo().performClick()
        assertEquals(listOf(ErrorPageAction.Reload, ErrorPageAction.GoBack), actions)
    }

    @Test
    fun `details reveal the address and certificate`() {
        compose.setContent {
            MaterialTheme {
                BrowserErrorPage(
                    error = PageLoadError(
                        url = "https://expired.example/",
                        type = PageLoadErrorType.InsecureConnection,
                        errorCode = "NET::ERR_CERT_DATE_INVALID",
                        certificateProblem = CertificateProblem.DateInvalid,
                        certificate = CertificateDetails("expired.example", "Example CA", null, null),
                    ),
                    canGoBack = false,
                    isReloading = false,
                    onAction = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.error_page_title_not_private)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.error_page_certificate_date)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.error_page_back_to_safety)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(string(R.string.error_page_show_details)).performScrollTo().performClick()
        compose.onNodeWithText("https://expired.example/").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Example CA").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `reload is disabled while a retry is loading`() {
        compose.setContent {
            MaterialTheme {
                BrowserErrorPage(
                    error = PageLoadError("https://example.com/", PageLoadErrorType.TimedOut, "ERR_TIMED_OUT"),
                    canGoBack = false,
                    isReloading = true,
                    onAction = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.error_page_reload)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `tab overview preview of an error page names the failure and the site`() {
        compose.setContent {
            MaterialTheme {
                BrowserErrorPagePreview(
                    error = PageLoadError("https://missing.example/a", PageLoadErrorType.HostNotFound, "ERR_NAME_NOT_RESOLVED"),
                )
            }
        }

        compose.onNodeWithText(string(R.string.error_page_title_unreachable)).assertIsDisplayed()
        compose.onNodeWithText("missing.example").assertIsDisplayed()
    }

    // ── Download button ───────────────────────────────────────────────

    @Test
    fun `download button opens the sheet on tap and hides from its long-press menu`() {
        var clicks = 0
        var hides = 0
        compose.setContent {
            MaterialTheme {
                BrowserDownloadButton(pageKey = "1", onClick = { clicks++ }, onHide = { hides++ })
            }
        }
        val button = compose.onNodeWithContentDescription(string(R.string.download_button_content_description))

        button.performClick()
        assertEquals(1, clicks)

        button.performTouchInput { longClick() }
        compose.onNodeWithText(string(R.string.download_button_hide)).performClick()
        assertEquals(1, hides)
        assertEquals(1, clicks)
    }

    // ── Home page settings ────────────────────────────────────────────

    @Test
    fun `custom home page is validated and saved normalized`() {
        var homePage: HomePage by mutableStateOf(HomePage.SearchEngineHome)
        compose.setContent {
            MaterialTheme {
                HomePageSettingsContent(
                    homePage = homePage,
                    searchEngine = SearchEngine.Google,
                    onHomePageChange = { homePage = it },
                    onBackClick = {},
                )
            }
        }

        compose.onNodeWithText(string(R.string.home_page_custom)).performClick()
        val field = compose.onNodeWithText(string(R.string.home_page_custom_label))

        field.performTextReplacement("not a url")
        field.performImeAction()
        compose.onNodeWithText(string(R.string.home_page_invalid_url)).assertIsDisplayed()
        assertEquals(HomePage.SearchEngineHome, homePage)

        field.performTextReplacement("Example.com/start")
        compose.onNodeWithText(string(R.string.save)).performClick()
        assertEquals(HomePage.Custom("https://example.com/start"), homePage)

        compose.onNodeWithText(string(R.string.home_page_search_engine)).performClick()
        assertEquals(HomePage.SearchEngineHome, homePage)
    }

    @Test
    fun `download button exposes hiding as an accessibility action`() {
        var hides = 0
        compose.setContent {
            MaterialTheme {
                BrowserDownloadButton(pageKey = "1", onClick = {}, onHide = { hides++ })
            }
        }
        val node = compose.onNodeWithContentDescription(string(R.string.download_button_content_description))
            .fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions]
            .single { it.label == string(R.string.download_button_hide) }

        compose.runOnIdle { action.action() }
        assertEquals(1, hides)
    }
}
