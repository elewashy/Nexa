package com.elewashy.nexa.ui.components.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSnackbarGestureTest {
    private val size = IntSize(width = 300, height = 80)
    private val flingThreshold = 800f

    @Test
    fun slightDragInEveryDirectionReturnsToOrigin() {
        listOf(
            Offset(20f, 0f),
            Offset(-20f, 0f),
            Offset(0f, 12f),
            Offset(0f, -12f),
            Offset(12f, 8f),
        ).forEach { offset ->
            assertFalse(
                shouldDismissSnackbar(offset, Velocity.Zero, size, flingThreshold),
            )
        }
    }

    @Test
    fun sufficientDistanceDismissesInEveryDirection() {
        listOf(
            Offset(121f, 0f),
            Offset(-121f, 0f),
            Offset(0f, 53f),
            Offset(0f, -53f),
        ).forEach { offset ->
            assertTrue(
                shouldDismissSnackbar(offset, Velocity.Zero, size, flingThreshold),
            )
        }
    }

    @Test
    fun fastOutwardFlingDismissesBeforeDistanceThreshold() {
        assertTrue(
            shouldDismissSnackbar(
                offset = Offset(20f, 0f),
                velocity = Velocity(1_200f, 0f),
                size = size,
                minimumFlingVelocity = flingThreshold,
            ),
        )
    }

    @Test
    fun reversingFlingRestoresSnackbar() {
        assertFalse(
            shouldDismissSnackbar(
                offset = Offset(40f, 0f),
                velocity = Velocity(-1_200f, 0f),
                size = size,
                minimumFlingVelocity = flingThreshold,
            ),
        )
    }

    @Test
    fun dismissTargetContinuesInReleaseDirection() {
        val target = snackbarDismissTarget(
            offset = Offset(-50f, 10f),
            velocity = Velocity(-1_000f, 200f),
            size = size,
        )

        assertTrue(target.x < 0f)
        assertTrue(target.y > 0f)
        assertTrue(target.getDistance() > size.width)
    }
}
