package com.elewashy.nexa.feature.share.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elewashy.nexa.feature.share.domain.model.MediaImage
import com.elewashy.nexa.feature.share.domain.model.VideoQuality

/**
 * Pure Compose bottom sheet for video/audio quality and image selection.
 * Wraps [QualitySelectionScreen] in a [ModalBottomSheet].
 *
 * Contains no XML or legacy View dependency.
 *
 * @param platform Platform name (e.g., "YouTube", "Facebook")
 * @param audioQualities List of audio quality options
 * @param videoQualities List of video quality options
 * @param images Images of the shared post, offered as a multi-select grid
 * @param selectedImageUrls URLs of the images selected for download
 * @param isLoading Whether currently loading/extracting
 * @param onDownload Callback when a video/audio quality is downloaded
 * @param onImageToggled Callback when an image's selection is toggled
 * @param onAllImagesToggled Callback for "Select all" / "Deselect all"
 * @param onDownloadImages Callback when the selected images are downloaded
 * @param onCancel Callback when cancelled or dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionSheet(
    platform: String,
    audioQualities: List<VideoQuality>,
    videoQualities: List<VideoQuality>,
    isLoading: Boolean,
    images: List<MediaImage> = emptyList(),
    selectedImageUrls: Set<String> = emptySet(),
    sizeLoading: Boolean = false,
    onDownload: (VideoQuality) -> Unit,
    onImageToggled: (MediaImage) -> Unit = {},
    onAllImagesToggled: () -> Unit = {},
    onDownloadImages: () -> Unit = {},
    onCancel: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            QualitySelectionScreen(
                modifier = Modifier.fillMaxWidth(),
                platform = platform,
                audioQualities = audioQualities,
                videoQualities = videoQualities,
                isLoading = isLoading,
                images = images,
                selectedImageUrls = selectedImageUrls,
                sizeLoading = sizeLoading,
                onDownload = onDownload,
                onImageToggled = onImageToggled,
                onAllImagesToggled = onAllImagesToggled,
                onDownloadImages = onDownloadImages,
                onCancel = onCancel,
            )
        }
    }
}
