package com.elewashy.nexa.feature.share.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import com.elewashy.nexa.ui.common.luminance
import com.elewashy.nexa.ui.icons.AudioFile
import com.elewashy.nexa.ui.icons.VideoFile
import com.elewashy.nexa.ui.components.buttons.GlassButton
import com.elewashy.nexa.feature.share.presentation.components.QualityGrid
import com.elewashy.nexa.feature.share.presentation.components.SectionHeader
import com.elewashy.nexa.ui.components.loading.ShimmerLoadingContent
import com.elewashy.nexa.ui.components.sheets.BottomSheetDragHandle
import com.elewashy.nexa.ui.components.sheets.BottomSheetHeader
import com.elewashy.nexa.ui.components.sheets.GlassBottomSheetContainer
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo

/**
 * Quality selection screen for video downloads
 * Shows shimmer loading while extracting, then displays quality options
 * 
 * @param platform Platform name (e.g., "YouTube", "Facebook")
 * @param audioQualities List of audio quality options
 * @param videoQualities List of video quality options
 * @param isLoading Whether currently loading/extracting
 * @param onDownload Callback when download button is clicked
 * @param onCancel Callback when cancel button is clicked
 */
@Composable
fun QualitySelectionScreen(
    platform: String,
    audioQualities: List<VideoQuality>,
    videoQualities: List<VideoQuality>,
    isLoading: Boolean,
    sizeLoading: Boolean = false,
    onDownload: (VideoQuality) -> Unit,
    onCancel: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val allQualities = audioQualities + videoQualities
    var selectedQuality by remember { mutableStateOf<VideoQuality?>(null) }
    val adaptiveInfo = rememberAdaptiveLayoutInfo()

    // Keep selectedQuality in sync when the qualities list is updated (e.g. size arrives).
    // Match by url so that a size-update copy() doesn't lose the selection.
    LaunchedEffect(allQualities) {
        selectedQuality = selectedQuality?.let { sel ->
            allQualities.find { it.url == sel.url }
        } ?: allQualities.firstOrNull()
    }

    val scrollState = rememberScrollState()

    GlassBottomSheetContainer(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = adaptiveInfo.sheetMaxWidth)
        ) {
            // Drag handle
            BottomSheetDragHandle(isDark = isDark)

            Spacer(modifier = Modifier.height(16.dp))

            // Header
            BottomSheetHeader(
                title = stringResource(R.string.select_quality),
                subtitle = if (platform.isNotEmpty()) platform else null,
                isDark = isDark
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Scrollable content with animated transition
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    .padding(bottom = 12.dp)
            ) {
                AnimatedContent(
                    targetState = isLoading,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(400)
                        ) togetherWith fadeOut(
                            animationSpec = tween(400)
                        )
                    },
                    label = "contentTransition"
                ) { loading ->
                    if (loading) {
                        // Show shimmer loading effect
                        ShimmerLoadingContent(isDark = isDark)
                    } else {
                        Column {
                            // Audio qualities grid (FIRST - at top)
                            if (audioQualities.isNotEmpty()) {
                                SectionHeader(
                                    title = stringResource(R.string.audio),
                                    icon = AudioFile,
                                    isDark = isDark
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                QualityGrid(
                                    qualities = audioQualities,
                                    selectedQuality = selectedQuality,
                                    isDark = isDark,
                                    sizeLoading = sizeLoading,
                                    onQualitySelected = { selectedQuality = it }
                                )
                            }

                            // Video qualities grid (SECOND - at bottom)
                            if (videoQualities.isNotEmpty()) {
                                if (audioQualities.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                                SectionHeader(
                                    title = stringResource(R.string.video),
                                    icon = VideoFile,
                                    isDark = isDark
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                QualityGrid(
                                    qualities = videoQualities,
                                    selectedQuality = selectedQuality,
                                    isDark = isDark,
                                    sizeLoading = sizeLoading,
                                    onQualitySelected = { selectedQuality = it }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Action buttons - ALWAYS VISIBLE at bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = if (isDark) {
                                listOf(
                                    Color(0xFF1C1C1E).copy(alpha = 0.0f),
                                    Color(0xFF1C1C1E).copy(alpha = 0.95f)
                                )
                            } else {
                                listOf(
                                    Color(0xFFF2F2F7).copy(alpha = 0.0f),
                                    Color(0xFFF2F2F7).copy(alpha = 0.98f)
                                )
                            }
                        )
                    )
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = adaptiveInfo.horizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassButton(
                        text = stringResource(R.string.cancel),
                        isPrimary = false,
                        isDark = isDark,
                        enabled = true,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )

                    GlassButton(
                        text = stringResource(R.string.download),
                        isPrimary = true,
                        isDark = isDark,
                        enabled = !isLoading && selectedQuality != null,
                        onClick = { selectedQuality?.let { onDownload(it) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
