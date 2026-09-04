package com.elewashy.nexa.ui.components.common

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class PillTabBarDirectionTest {
    @Test
    fun `ltr keeps logical pager position`() {
        assertEquals(0.25f, visualPagerPosition(0.25f, 2, LayoutDirection.Ltr), 0f)
    }

    @Test
    fun `rtl mirrors logical pager position including transition offset`() {
        assertEquals(0.75f, visualPagerPosition(0.25f, 2, LayoutDirection.Rtl), 0f)
        assertEquals(0f, visualPagerPosition(1f, 2, LayoutDirection.Rtl), 0f)
    }
}
