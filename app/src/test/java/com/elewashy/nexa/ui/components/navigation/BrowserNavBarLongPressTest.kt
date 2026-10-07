package com.elewashy.nexa.ui.components.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.elewashy.nexa.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserNavBarLongPressTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private val calls = mutableListOf<String>()

    private val state = BrowserNavBarState(
        toolbarVisible = true,
        backEnabled = false,
        forwardEnabled = false,
        refreshVisible = true,
        homeVisible = true,
        moreOptionsVisible = true,
        linkButtonVisible = true,
        addressPreviewVisible = false,
        urlText = "https://example.com/",
        pageTitle = "Example",
        progressPercent = null,
        currentUrl = "https://example.com/",
        tabCount = 3,
        isPrivate = false,
        canBookmarkCurrentPage = true,
        isCurrentPageBookmarked = false,
    )

    private fun actions(privateBrowsingAvailable: Boolean = true) = BrowserNavBarActions(
        onRefresh = { calls += "refresh" },
        onOpenSearch = { calls += "openSearch" },
        onHome = { calls += "home" },
        onTabs = { calls += "tabs" },
        onBack = { calls += "back" },
        onForward = { calls += "forward" },
        onShare = { calls += "share" },
        onNewTab = { calls += "newTabCurrentMode" },
        onNewRegularTab = { calls += "newRegularTab" },
        onNewPrivateTab = if (privateBrowsingAvailable) {
            { calls += "newPrivateTab" }
        } else {
            null
        },
        onBookmarks = { calls += "bookmarks" },
        onToggleBookmark = { calls += "toggleBookmark" },
        onDownloads = { calls += "downloads" },
        onHistory = { calls += "history" },
        onSettings = { calls += "settings" },
        onAdBlocker = { calls += "adBlocker" },
        onSetSiteAdBlocking = { calls += "siteAdBlocking" },
    )

    private fun setBottomBar(actions: BrowserNavBarActions) {
        compose.setContent {
            MaterialTheme {
                BrowserNavBar(
                    state = state,
                    pageFavicon = null,
                    actions = actions,
                    onToggleAddressPreview = { calls += "toggleAddressPreview" },
                    onDismissAddressPreview = { calls += "dismissAddressPreview" },
                )
            }
        }
    }

    private fun tabsButton() = compose.onNodeWithContentDescription(string(R.string.tabs_count_accessibility, 3))

    @Test
    fun `tapping the tabs button opens the tab switcher`() {
        setBottomBar(actions())

        tabsButton().performClick()

        assertEquals(listOf("tabs"), calls)
    }

    @Test
    fun `long-pressing the tabs button offers a new tab`() {
        setBottomBar(actions())

        tabsButton().performTouchInput { longClick() }
        compose.onNodeWithText(string(R.string.new_tab)).assertIsDisplayed().performClick()

        assertEquals(listOf("newRegularTab"), calls)
        // The menu closes once an option is chosen.
        compose.onNodeWithText(string(R.string.new_tab)).assertDoesNotExist()
    }

    @Test
    fun `long-pressing the tabs button offers incognito browsing`() {
        setBottomBar(actions())

        tabsButton().performTouchInput { longClick() }
        compose.onNodeWithText(string(R.string.new_private_tab)).assertIsDisplayed().performClick()

        assertEquals(listOf("newPrivateTab"), calls)
    }

    @Test
    fun `incognito is not offered where it is unsupported`() {
        setBottomBar(actions(privateBrowsingAvailable = false))

        tabsButton().performTouchInput { longClick() }

        compose.onNodeWithText(string(R.string.new_tab)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.new_private_tab)).assertDoesNotExist()
    }

    @Test
    fun `search button shows the address preview on tap and opens the search bar on long-press`() {
        setBottomBar(actions())
        val search = compose.onNodeWithContentDescription(string(R.string.search))

        search.performClick()
        assertEquals(listOf("toggleAddressPreview"), calls)

        search.performTouchInput { longClick() }
        assertEquals(listOf("toggleAddressPreview", "openSearch"), calls)
    }
}
