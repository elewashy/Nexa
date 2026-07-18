package com.elewashy.nexa.ui.adaptive

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class WindowSizeClassTest {

    @Test
    fun `window width breakpoints match Material adaptive navigation guidance`() {
        assertEquals(WindowWidthSizeClass.Compact, sizeClassFor(599).widthSizeClass)
        assertEquals(WindowWidthSizeClass.Medium, sizeClassFor(600).widthSizeClass)
        assertEquals(WindowWidthSizeClass.Medium, sizeClassFor(839).widthSizeClass)
        assertEquals(WindowWidthSizeClass.Expanded, sizeClassFor(840).widthSizeClass)
    }

    private fun sizeClassFor(widthDp: Int) = WindowSizeClass.calculateFromSize(
        size = DpSize(widthDp.dp, 900.dp),
    )
}
