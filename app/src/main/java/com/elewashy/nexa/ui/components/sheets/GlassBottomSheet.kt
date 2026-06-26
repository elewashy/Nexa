package com.elewashy.nexa.ui.components.sheets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Facebook
import compose.icons.fontawesomeicons.brands.Instagram
import compose.icons.fontawesomeicons.brands.Threads
import compose.icons.fontawesomeicons.brands.Tiktok
import compose.icons.fontawesomeicons.brands.XTwitter
import compose.icons.fontawesomeicons.brands.Youtube

/**
 * Glass-morphism styled bottom sheet container with entrance animation
 * 
 * @param isDark Whether dark theme is active
 * @param content Sheet content
 */
@Composable
fun GlassBottomSheetContainer(
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    // Entrance animation
    val sheetAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        sheetAnim.animateTo(1f, spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ))
    }

    Box(
        modifier = Modifier
            .widthIn(max = adaptiveInfo.sheetMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .graphicsLayer {
                alpha = sheetAnim.value
                translationY = (1f - sheetAnim.value) * 60f
            }
    ) {
        // Main glass container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(
                                Color(0xFF1C1C1E).copy(alpha = 0.85f),
                                Color(0xFF1C1C1E).copy(alpha = 0.92f)
                            )
                        } else {
                            listOf(
                                Color(0xFFF8F8FF).copy(alpha = 0.88f),
                                Color(0xFFF2F2F7).copy(alpha = 0.95f)
                            )
                        }
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.White.copy(alpha = 0.04f)
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.8f),
                                Color.White.copy(alpha = 0.3f)
                            )
                        }
                    ),
                    shape = RoundedCornerShape(30.dp)
                )
        ) {
            content()
        }
    }
}

/**
 * Bottom sheet drag handle
 * 
 * @param isDark Whether dark theme is active
 */
@Composable
fun BottomSheetDragHandle(isDark: Boolean) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(5.dp)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(2.5.dp))
            .background(
                if (isDark) Color.White.copy(alpha = 0.3f)
                else Color.Black.copy(alpha = 0.2f)
            )
    )
}

/**
 * Bottom sheet header with title and optional subtitle with platform logo
 * 
 * @param title Main title
 * @param subtitle Optional subtitle (platform name)
 * @param isDark Whether dark theme is active
 */
@Composable
fun BottomSheetHeader(
    title: String,
    subtitle: String? = null,
    isDark: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White else Color(0xFF1C1C1E),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // Platform name with logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // Platform logo (no tint to preserve original colors)
                val platformIcon = getPlatformIcon(subtitle)
                if (platformIcon != null) {
                    Icon(
                        imageVector = platformIcon,
                        contentDescription = subtitle,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White.copy(alpha = 0.6f)
                    else Color.Black.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Get platform icon based on platform name
 */
private fun getPlatformIcon(platform: String): ImageVector? {
    return when {
        platform.contains("YouTube", ignoreCase = true) -> FontAwesomeIcons.Brands.Youtube
        platform.contains("Facebook", ignoreCase = true) -> FontAwesomeIcons.Brands.Facebook
        platform.contains("Instagram", ignoreCase = true) -> FontAwesomeIcons.Brands.Instagram
        platform.contains("Threads", ignoreCase = true) -> FontAwesomeIcons.Brands.Threads
        platform.contains("TikTok", ignoreCase = true) -> FontAwesomeIcons.Brands.Tiktok
        platform.contains("Twitter", ignoreCase = true) || platform.contains("X", ignoreCase = true) -> FontAwesomeIcons.Brands.XTwitter
        else -> null
    }
}
