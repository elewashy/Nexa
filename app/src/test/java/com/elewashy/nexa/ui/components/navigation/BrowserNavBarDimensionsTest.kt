package com.elewashy.nexa.ui.components.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavBarDimensionsTest {
    @Test
    fun `compact top and bottom toolbars consume the same shared row height`() {
        val dimensions = browserNavBarDimensionsFor(
            screenWidthDp = 360,
            screenHeightDp = 800,
            isTvLike = false,
        )

        assertEquals(52.dp, dimensions.navBarHeight)
        assertTrue(dimensions.actionSize >= 48.dp)
    }

    @Test
    fun `short windows preserve minimum touch targets`() {
        val dimensions = browserNavBarDimensionsFor(
            screenWidthDp = 700,
            screenHeightDp = 500,
            isTvLike = false,
        )

        assertEquals(56.dp, dimensions.navBarHeight)
        assertEquals(48.dp, dimensions.actionSize)
    }
}
