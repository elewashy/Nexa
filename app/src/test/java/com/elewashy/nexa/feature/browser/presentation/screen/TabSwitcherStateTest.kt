package com.elewashy.nexa.feature.browser.presentation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwitcherStateTest {
    @Test
    fun `incognito segment is absent before an incognito tab exists`() {
        assertFalse(shouldShowPrivateMode(privateCount = 0, currentPage = NORMAL_PAGE))
    }

    @Test
    fun `incognito segment remains stable while last incognito page resets`() {
        assertTrue(shouldShowPrivateMode(privateCount = 0, currentPage = PRIVATE_PAGE))
        assertFalse(shouldShowPrivateMode(privateCount = 0, currentPage = NORMAL_PAGE))
    }

    @Test
    fun `active workspace resolves only to an available incognito page`() {
        assertEquals(PRIVATE_PAGE, workspacePageFor(activeIsPrivate = true, privateTabsExist = true))
        assertEquals(NORMAL_PAGE, workspacePageFor(activeIsPrivate = true, privateTabsExist = false))
        assertEquals(NORMAL_PAGE, workspacePageFor(activeIsPrivate = false, privateTabsExist = true))
    }
}
