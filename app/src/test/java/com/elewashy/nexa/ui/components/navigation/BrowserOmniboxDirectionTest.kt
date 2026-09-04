package com.elewashy.nexa.ui.components.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserOmniboxDirectionTest {
    @Test
    fun `populate arrow mirrors from query content rather than app locale`() {
        assertEquals(1f, populateIconScaleX("weather today"))
        assertEquals(-1f, populateIconScaleX("طقس اليوم"))
        assertEquals(-1f, populateIconScaleX("2026 - طقس اليوم"))
        assertEquals(1f, populateIconScaleX("Google بحث"))
    }
}
