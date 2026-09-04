package com.elewashy.nexa.ui.components.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEmptyStateDimensionsTest {
    @Test
    fun compactWidthUsesSmallGeometry() {
        val dimensions = emptyStateDimensions(280.dp, 700.dp)

        assertEquals(56.dp, dimensions.badgeSize)
        assertEquals(26.dp, dimensions.iconSize)
    }

    @Test
    fun compactHeightUsesSmallGeometryInLandscapeAndEmbeddedContent() {
        val dimensions = emptyStateDimensions(700.dp, 320.dp)

        assertEquals(56.dp, dimensions.badgeSize)
        assertEquals(14.dp, dimensions.iconTitleSpacing)
    }

    @Test
    fun typicalPhoneUsesRestrainedStandardGeometry() {
        val dimensions = emptyStateDimensions(360.dp, 640.dp)

        assertEquals(80.dp, dimensions.badgeSize)
        assertEquals(36.dp, dimensions.iconSize)
        assertTrue(dimensions.iconTitleSpacing < dimensions.badgeSize)
    }

    @Test
    fun expandedWindowUsesLargerButBoundedGeometry() {
        val dimensions = emptyStateDimensions(1_200.dp, 800.dp)

        assertEquals(96.dp, dimensions.badgeSize)
        assertEquals(44.dp, dimensions.iconSize)
        assertEquals(560.dp, dimensions.contentMaxWidth)
    }

    @Test
    fun unboundedEmbeddedHeightDoesNotForceExpandedGeometry() {
        val dimensions = emptyStateDimensions(400.dp, Dp.Infinity)

        assertEquals(80.dp, dimensions.badgeSize)
    }

    @Test
    fun polygonIsNormalizedToItsLayoutBounds() {
        val bounds = createEmptyStatePolygon().calculateBounds()

        assertTrue(bounds[0] >= 0f)
        assertTrue(bounds[1] >= 0f)
        assertTrue(bounds[2] <= 1f)
        assertTrue(bounds[3] <= 1f)
    }
}
