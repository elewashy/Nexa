package com.elewashy.nexa.feature.share.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.icons.WaterDrop

/**
 * Glass-morphism styled quality selection tile
 * 
 * @param quality Video quality option
 * @param isSelected Whether this tile is currently selected
 * @param isDark Whether dark theme is active
 * @param sizeLoading Whether the size is still being fetched
 * @param onClick Click handler
 */
@Composable
fun GlassTile(
    quality: VideoQuality,
    isSelected: Boolean,
    isDark: Boolean,
    sizeLoading: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val displayLabels = quality.getDisplayLabels()
    val qualityLabel = if (displayLabels.quality.equals("Watermark", ignoreCase = true)) {
        stringResource(R.string.watermark)
    } else {
        displayLabels.quality
    }
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "tileScale"
    )

    val accentColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .height(if (adaptiveInfo.isTvLike) 72.dp else 58.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = if (isSelected) {
                    Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(
                                accentColor.copy(alpha = 0.25f),
                                accentColor.copy(alpha = 0.18f)
                            )
                        } else {
                            listOf(
                                accentColor.copy(alpha = 0.15f),
                                accentColor.copy(alpha = 0.10f)
                            )
                        }
                    )
                } else {
                    Brush.verticalGradient(
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
                }
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.8.dp,
                brush = if (isSelected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.8f),
                            accentColor.copy(alpha = 0.6f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
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
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
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

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = qualityLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) {
                    if (isDark) Color.White else accentColor
                } else {
                    if (isDark) Color.White.copy(alpha = 0.85f)
                    else Color.Black.copy(alpha = 0.75f)
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )

            if (displayLabels.metadata != null) {
                Text(
                    text = displayLabels.metadata,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        if (isDark) Color.White.copy(alpha = 0.78f) else accentColor.copy(alpha = 0.78f)
                    } else {
                        if (isDark) Color.White.copy(alpha = 0.55f)
                        else Color.Black.copy(alpha = 0.48f)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 11.sp
                )
            } else if (sizeLoading) {
                SizeShimmer(isDark = isDark)
            }
        }

        // Corner ribbon for watermark (top-left)
        if (quality.hasWatermark) {
            WatermarkRibbon(isDark = isDark)
        }

        // Selection indicator glow (top-right corner)
        if (isSelected) {
            SelectionIndicator(accentColor = accentColor)
        }
    }
}

@Composable
private fun WatermarkRibbon(isDark: Boolean) {
    Box(
        modifier = Modifier
            .size(35.dp)
            .drawBehind {
                // Draw triangle ribbon
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(0f, size.height)
                    close()
                }
                
                // Shadow
                drawPath(
                    path = path,
                    color = Color.Black.copy(alpha = 0.2f)
                )
                
                // Main ribbon with theme-aware colors
                val ribbonColors = if (isDark) {
                    listOf(
                        Color(0xFF0A84FF).copy(alpha = 0.95f),
                        Color(0xFF0A84FF).copy(alpha = 0.85f)
                    )
                } else {
                    listOf(
                        Color(0xFF007AFF).copy(alpha = 0.95f),
                        Color(0xFF007AFF).copy(alpha = 0.85f)
                    )
                }
                
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = ribbonColors,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    )
                )
            },
        contentAlignment = Alignment.TopStart
    ) {
        // Watermark icon - centered in triangle
        Icon(
            imageVector = WaterDrop,
            contentDescription = stringResource(R.string.watermark),
            tint = Color.White,
            modifier = Modifier
                .size(12.dp)
                .offset(
                    x = (35.dp / 3) - 6.dp,
                    y = (35.dp / 3) - 6.dp
                )
        )
    }
}

@Composable
private fun SelectionIndicator(accentColor: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .padding(5.dp)
            .clip(RoundedCornerShape(3.5.dp))
            .background(accentColor)
            .drawBehind {
                // Soft glow effect
                drawCircle(
                    color = accentColor.copy(alpha = 0.3f),
                    radius = size.width * 1.5f
                )
            }
    )
}

@Composable
private fun SizeShimmer(isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "sizeShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(8.dp)
            .clip(CircleShape)
            .background(
                if (isDark) Color.White.copy(alpha = alpha)
                else Color.Black.copy(alpha = alpha * 0.6f)
            )
    )
}
