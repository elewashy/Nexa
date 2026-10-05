package com.elewashy.nexa.feature.onboarding.presentation

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.permissions.AppPermission
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.browser.presentation.labelRes
import com.elewashy.nexa.ui.permissions.rememberPermissionsRequestState
import com.elewashy.nexa.ui.startup.StartupDestination
import com.elewashy.nexa.ui.startup.StartupHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private val next get() = string(R.string.onboarding_next)
    private val back get() = string(R.string.back)
    private val skip get() = string(R.string.onboarding_skip)
    private val skipForNow get() = string(R.string.onboarding_skip_for_now)
    private val getStarted get() = string(R.string.onboarding_get_started)
    private val welcomeTitle get() = string(R.string.onboarding_welcome_title, string(R.string.app_name))
    private val permissionsTitle get() = string(R.string.onboarding_permissions_title)
    private val searchTitle get() = string(R.string.onboarding_search_title)
    private val appearanceTitle get() = string(R.string.onboarding_appearance_title)
    private val installApps get() = string(R.string.permission_install_apps)
    private val storage get() = string(R.string.permission_storage)

    private var finished = 0
    private var openedTheme = 0
    private var openedNavigationBar = 0
    private var state by mutableStateOf(OnboardingUiState())

    /** System grant state seen by the screen (Robolectric cannot answer the storage check). */
    private val granted = mutableSetOf<AppPermission>()

    private fun setScreen(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            MaterialTheme {
                OnboardingScreen(
                    state = state,
                    permissions = rememberPermissionsRequestState(checkGranted = { _, p -> p in granted }),
                    onSearchEngineSelected = { state = state.copy(searchEngine = it) },
                    onOpenTheme = { openedTheme++ },
                    onOpenNavigationBarPosition = { openedNavigationBar++ },
                    onFinish = { finished++ },
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    /** Leaves and re-enters the foreground, as when returning from a Settings page. */
    private fun resumeAgain() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun `walks through every page and finishes once`() {
        setScreen()
        compose.onNodeWithText(welcomeTitle).assertExists()
        compose.onNodeWithText(back).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.onboarding_step_progress, 1, 4)).assertExists()

        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(permissionsTitle).assertExists()
        compose.onNodeWithContentDescription(string(R.string.onboarding_step_progress, 2, 4)).assertExists()

        // Nothing granted on a fresh install: moving on is an explicit skip.
        compose.onNodeWithText(skipForNow).performClick()
        compose.onNodeWithText(searchTitle).assertExists()

        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(appearanceTitle).assertExists()
        // The last page has no Skip: its primary action finishes.
        compose.onNodeWithText(skip).assertDoesNotExist()

        compose.onNodeWithText(getStarted).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `skip finishes from the first page`() {
        setScreen()
        compose.onNodeWithText(skip).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `back steps to the previous page, and is left to the system on the first page`() {
        setScreen()
        val dispatcher = compose.activity.onBackPressedDispatcher
        assertFalse("first page must not intercept Back", dispatcher.hasEnabledCallbacks())

        compose.onNodeWithText(next).performClick()
        compose.waitForIdle()
        assertTrue(dispatcher.hasEnabledCallbacks())

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.onNodeWithText(welcomeTitle).assertExists()
        assertFalse(dispatcher.hasEnabledCallbacks())

        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(back).performClick()
        compose.onNodeWithText(welcomeTitle).assertExists()
        assertEquals(0, finished)
    }

    @Test
    fun `permissions can be granted from the page, and the page reflects grants made in Settings`() {
        setScreen()
        compose.onNodeWithText(next).performClick()

        // Install unknown apps has no dialog: Grant opens this app's Settings page.
        compose.onNodeWithContentDescription(string(R.string.permission_grant_named, installApps))
            .performScrollTo()
            .performClick()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.intent.action)
        assertEquals("package:${compose.activity.packageName}", started.intent.dataString)

        // The user allows it in Settings and comes back.
        granted += AppPermission.InstallPackages
        resumeAgain()
        compose.onNodeWithContentDescription(string(R.string.permission_grant_named, installApps))
            .assertDoesNotExist()

        // Storage still missing: still a skip.
        compose.onNodeWithText(skipForNow).assertExists()
        compose.onNodeWithContentDescription(string(R.string.permission_grant_named, storage)).assertExists()
    }

    @Test
    fun `with every permission granted the step simply continues`() {
        granted += AppPermission.requestable
        setScreen()
        compose.onNodeWithText(next).performClick()

        compose.onNodeWithText(permissionsTitle).assertExists()
        compose.onNodeWithText(skipForNow).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.permission_grant)).assertDoesNotExist()

        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(searchTitle).assertExists()
    }

    @Test
    fun `search engine is picked inline, theme and toolbar open their real pages`() {
        setScreen()
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(skipForNow).performClick()

        val duckDuckGo = string(SearchEngine.DuckDuckGo.labelRes)
        compose.onNodeWithText(duckDuckGo).performScrollTo().performClick()
        assertEquals(SearchEngine.DuckDuckGo, state.searchEngine)
        compose.onNodeWithText(duckDuckGo).assertIsSelected()

        compose.onNodeWithText(next).performClick()
        // Current values are shown on the rows.
        compose.onNodeWithText(string(R.string.theme_system)).assertExists()
        compose.onNodeWithText(string(R.string.navigation_bar_position_bottom)).assertExists()

        compose.onNodeWithText(string(R.string.onboarding_appearance_theme_title)).performClick()
        assertEquals(1, openedTheme)
        compose.onNodeWithText(string(R.string.navigation_bar_position)).performScrollTo().performClick()
        assertEquals(1, openedNavigationBar)
        assertEquals(0, finished)
    }

    @Test
    fun `the open page survives recreation and process death`() {
        val restoration = StateRestorationTester(compose)
        setScreen(restoration)
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(skipForNow).performClick()
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(appearanceTitle).assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(appearanceTitle).assertExists()
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun `large landscape windows use two panes and hide the phone-only toolbar choice`() {
        setScreen()
        compose.onNodeWithText(welcomeTitle).assertExists()
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(skipForNow).performClick()
        compose.onNodeWithText(next).performClick()

        compose.onNodeWithText(appearanceTitle).assertExists()
        compose.onNodeWithText(string(R.string.onboarding_appearance_theme_title)).assertExists()
        // Medium and larger windows show a navigation rail, so the bar position does not apply.
        compose.onNodeWithText(string(R.string.navigation_bar_position)).assertDoesNotExist()

        compose.onNodeWithText(getStarted).performClick()
        assertEquals(1, finished)
    }

    @Test
    @Config(qualifiers = "ar")
    fun `right-to-left locales complete the flow`() {
        setScreen()
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(skipForNow).performClick()
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(getStarted).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `startup host shows nothing while loading and replaces onboarding with the browser`() {
        var destination by mutableStateOf(StartupDestination.Loading)
        compose.setContent {
            StartupHost(
                destination = destination,
                onboarding = { Text("onboarding") },
                browser = { Text("browser") },
            )
        }
        compose.onNodeWithText("onboarding").assertDoesNotExist()
        compose.onNodeWithText("browser").assertDoesNotExist()

        destination = StartupDestination.Onboarding
        compose.onNodeWithText("onboarding").assertExists()
        compose.onNodeWithText("browser").assertDoesNotExist()

        destination = StartupDestination.Browser
        compose.waitForIdle()
        compose.onNodeWithText("browser").assertExists()
        compose.onNodeWithText("onboarding").assertDoesNotExist()
    }
}
