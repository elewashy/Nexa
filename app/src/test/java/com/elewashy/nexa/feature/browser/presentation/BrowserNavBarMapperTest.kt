package com.elewashy.nexa.feature.browser.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
