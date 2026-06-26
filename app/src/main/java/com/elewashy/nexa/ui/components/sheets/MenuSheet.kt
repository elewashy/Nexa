package com.elewashy.nexa.ui.components.sheets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Horizontal row of menu items with staggered entrance animation
 * 
 * @param items List of composable items to display
 */
@Composable
fun AnimatedMenuRow(
    items: List<@Composable (Int) -> Unit>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        items.forEachIndexed { idx, item ->
            val animatable = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 220,
                        delayMillis = idx * 35
                    )
                )
            }

            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = animatable.value
                    translationY = (1f - animatable.value) * 16f
                }
            ) {
                item(idx)
            }
        }
    }
}
