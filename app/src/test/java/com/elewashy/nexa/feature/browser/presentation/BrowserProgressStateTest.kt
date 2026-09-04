package com.elewashy.nexa.feature.browser.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserProgressStateTest {
    @Test
    fun `progress events cannot create a loading state without page start`() {
        assertEquals(ProgressState.Hidden, ProgressState.Hidden.withWebProgress(45))
    }

    @Test
    fun `active progress is bounded monotonic coarsened and hidden at completion`() {
        val started = ProgressState.Loading(0)
        assertEquals(ProgressState.Loading(0), started.withWebProgress(-10))
        assertEquals(ProgressState.Loading(0), started.withWebProgress(4))

        val advanced = started.withWebProgress(5)
        assertEquals(ProgressState.Loading(5), advanced)
        assertEquals(advanced, advanced.withWebProgress(3))
        assertEquals(advanced, advanced.withWebProgress(9))
        assertEquals(ProgressState.Loading(52), advanced.withWebProgress(52))
        assertEquals(ProgressState.Hidden, advanced.withWebProgress(100))
        assertEquals(ProgressState.Hidden, advanced.withWebProgress(500))
    }
}
