package com.elewashy.nexa.feature.browser.presentation

import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavBarMapperTest {
    @Test
    fun `toolbar progress is exposed only while visibly loading`() {
        assertNull(BrowserUiState(progress = ProgressState.Hidden).toNavBarState(false).progressPercent)
        assertNull(BrowserUiState(progress = ProgressState.Loading(0)).toNavBarState(false).progressPercent)
        assertEquals(
            45,
            BrowserUiState(progress = ProgressState.Loading(45)).toNavBarState(false).progressPercent,
        )
        assertNull(BrowserUiState(progress = ProgressState.Loading(100)).toNavBarState(false).progressPercent)
    }

    @Test
    fun `back stays enabled at the first page of a tab opened by a live tab`() {
        fun tab(id: Long, opener: Long? = null) = TabItem(
            id = id, url = "https://$id.example/", title = "", position = id.toInt(),
            isActive = false, createdAt = id, lastAccessedAt = id, openerTabId = opener,
        )
        val popup = TabWorkspaceState(tabs = listOf(tab(1), tab(2, opener = 1)), activeTabId = 2)
        val root = TabWorkspaceState(tabs = listOf(tab(1), tab(2)), activeTabId = 2)

        assertTrue(BrowserUiState(backButtonEnabled = false).toNavBarState(false, popup).backEnabled)
        assertFalse(BrowserUiState(backButtonEnabled = false).toNavBarState(false, root).backEnabled)
        assertTrue(BrowserUiState(backButtonEnabled = true).toNavBarState(false, root).backEnabled)
    }
}
