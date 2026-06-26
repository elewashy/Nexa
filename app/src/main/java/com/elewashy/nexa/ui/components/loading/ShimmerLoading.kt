package com.elewashy.nexa.ui.components.loading

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer loading effect with 3x3 grid of glassy tiles
 * 
 * @param isDark Whether dark theme is active
 */
@Composable
fun ShimmerLoadingContent(isDark: Boolean) {
    // Shimmer animation using LaunchedEffect and Animatable
    val shimmerTranslate = remember { Animatable(-1f) }
    
    LaunchedEffect(Unit) {
        while (true) {
            shimmerTranslate.animateTo(
                targetValue = 1f,
                animationSpec = tween(1500, easing = LinearEasing)
            )
            shimmerTranslate.snapTo(-1f)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Show 3 rows of shimmer tiles (3x3 grid)
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) {
                    Box(modifier = Modifier.weight(1f)) {
                        ShimmerTile(
                            isDark = isDark,
                            shimmerTranslate = shimmerTranslate.value
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual shimmer tile with glassy effect
 * 
 * @param isDark Whether dark theme is active
 * @param shimmerTranslate Animation value for shimmer effect (-1f to 1f)
 */
@Composable
fun ShimmerTile(
    isDark: Boolean,
    shimmerTranslate: Float
) {
    Box(
        modifier = Modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.04f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.7f),
                            Color.White.copy(alpha = 0.5f)
                        )
                    }
                )
            )
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.9f),
                            Color.White.copy(alpha = 0.4f)
                        )
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        // Shimmer overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val shimmerWidth = size.width * 0.5f
                    val shimmerStart = (shimmerTranslate * size.width) - shimmerWidth / 2
                    
                    // Create glassy shimmer gradient
                    val shimmerBrush = Brush.horizontalGradient(
                        colors = if (isDark) {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.15f),
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        } else {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.4f),
                                Color.White.copy(alpha = 0.7f),
                                Color.White.copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        },
                        startX = shimmerStart,
                        endX = shimmerStart + shimmerWidth
                    )
                    
                    drawRect(shimmerBrush)
                }
        )
        
        // Inner highlight (glass reflection)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .height(1.dp)
                .align(Alignment.TopCenter)
                .padding(top = 5.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            if (isDark) Color.White.copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(0.5.dp)
                )
        )
    }
}
