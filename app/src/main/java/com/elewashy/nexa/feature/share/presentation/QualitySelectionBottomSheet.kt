package com.elewashy.nexa.feature.share.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.elewashy.nexa.feature.share.domain.model.VideoQuality

/**
 * Pure Compose bottom sheet for video quality selection.
 * Wraps [QualitySelectionScreen] in a [ModalBottomSheet].
 *
 * Contains no XML or legacy View dependency.
 *
 * @param platform Platform name (e.g., "YouTube", "Facebook")
 * @param audioQualities List of audio quality options
 * @param videoQualities List of video quality options
 * @param isLoading Whether currently loading/extracting
 * @param onDownload Callback when download button is clicked
 * @param onCancel Callback when cancelled or dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionSheet(
    platform: String,
    audioQualities: List<VideoQuality>,
    videoQualities: List<VideoQuality>,
    isLoading: Boolean,
    sizeLoading: Boolean = false,
    onDownload: (VideoQuality) -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Transparent,
        dragHandle = null,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            QualitySelectionScreen(
                platform = platform,
                audioQualities = audioQualities,
                videoQualities = videoQualities,
                isLoading = isLoading,
                sizeLoading = sizeLoading,
                onDownload = onDownload,
                onCancel = onCancel
            )
        }
    }
}
