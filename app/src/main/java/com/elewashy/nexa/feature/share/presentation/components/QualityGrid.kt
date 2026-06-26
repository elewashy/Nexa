package com.elewashy.nexa.feature.share.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import com.elewashy.nexa.ui.adaptive.adaptiveGridColumns
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo

/**
 * Adaptive grid layout for quality tiles.
 * 
 * @param qualities List of video qualities to display
 * @param selectedQuality Currently selected quality
 * @param isDark Whether dark theme is active
 * @param onQualitySelected Callback when a quality is selected
 */
@Composable
fun QualityGrid(
    qualities: List<VideoQuality>,
    selectedQuality: VideoQuality?,
    isDark: Boolean,
    sizeLoading: Boolean = false,
    onQualitySelected: (VideoQuality) -> Unit
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = adaptiveInfo.horizontalPadding),
    ) {
        val columns = adaptiveGridColumns(
            availableWidth = maxWidth,
            minCellWidth = if (adaptiveInfo.isCompact) 96.dp else 128.dp,
            minColumns = if (adaptiveInfo.widthDp < 360) 2 else 3,
            maxColumns = if (adaptiveInfo.isExpanded) 5 else 4,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            qualities.chunked(columns).forEach { rowQualities ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowQualities.forEach { quality ->
                        Box(modifier = Modifier.weight(1f)) {
                            GlassTile(
                                quality = quality,
                                isSelected = selectedQuality == quality,
                                isDark = isDark,
                                sizeLoading = sizeLoading && quality.size == null,
                                onClick = { onQualitySelected(quality) }
                            )
                        }
                    }
                    repeat(columns - rowQualities.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Section header with icon and title
 * 
 * @param title Section title
 * @param icon Material Symbol image vector
 * @param isDark Whether dark theme is active
 */
@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector,
    isDark: Boolean
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = adaptiveInfo.horizontalPadding)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (isDark) Color.White.copy(alpha = 0.6f)
            else Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White.copy(alpha = 0.6f)
            else Color.Black.copy(alpha = 0.5f),
            letterSpacing = 1.sp
        )
    }
}
