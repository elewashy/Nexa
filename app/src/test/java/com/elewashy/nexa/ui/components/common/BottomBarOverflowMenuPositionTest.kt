package com.elewashy.nexa.ui.components.common

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarOverflowMenuPositionTest {

    @Test
    fun `ltr bottom menu uses small physical right edge margin`() {
        val popup = IntSize(width = 280, height = 400)
        val offset = bottomBarOverflowMenuOffset(
            anchorBounds = IntRect(390, 820, 450, 880),
            windowSize = IntSize(470, 900),
            popupContentSize = popup,
            layoutDirection = LayoutDirection.Ltr,
            edgeMarginPx = 8,
        )

        assertEquals(470 - popup.width - 8, offset.x)
        assertEquals(820 - popup.height, offset.y)
    }

    @Test
    fun `rtl bottom menu mirrors to small physical left edge margin`() {
        val popup = IntSize(width = 280, height = 400)
        val offset = bottomBarOverflowMenuOffset(
            anchorBounds = IntRect(20, 820, 80, 880),
            windowSize = IntSize(470, 900),
            popupContentSize = popup,
            layoutDirection = LayoutDirection.Rtl,
            edgeMarginPx = 8,
        )

        assertEquals(8, offset.x)
        assertEquals(820 - popup.height, offset.y)
    }

    @Test
    fun `short window preserves top safety margin`() {
        val offset = bottomBarOverflowMenuOffset(
            anchorBounds = IntRect(390, 300, 450, 360),
            windowSize = IntSize(470, 400),
            popupContentSize = IntSize(width = 280, height = 400),
            layoutDirection = LayoutDirection.Ltr,
            edgeMarginPx = 8,
        )

        assertEquals(8, offset.y)
    }
}
