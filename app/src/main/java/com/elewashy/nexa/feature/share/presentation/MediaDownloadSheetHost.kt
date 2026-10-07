package com.elewashy.nexa.feature.share.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import com.elewashy.nexa.ui.permissions.DownloadPermissionGate

/**
 * The media download sheet for [url]: extracts the page's media and lets the user pick a quality
 * or images. Shared by the browser's download button and the system share target, so both behave
 * identically.
 *
 * [onClose] runs once the session is over — after a download started, the extraction failed, or
 * the user dismissed the sheet; [onMessage] receives the feedback to show (download started,
 * extraction failure).
 */
@Composable
fun MediaDownloadSheetHost(
    url: String?,
    viewModel: ShareViewModel,
    downloadPermissionGate: DownloadPermissionGate,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnMessage by rememberUpdatedState(onMessage)
    val currentOnClose by rememberUpdatedState(onClose)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShareEvent.Close -> {
                    event.message?.let(currentOnMessage)
                    currentOnClose()
                }
            }
        }
    }

    LaunchedEffect(url) { viewModel.handleSharedText(url) }

    if (state.showSheet) {
        val audioQualities = remember(state.qualities) {
            state.qualities.filter { it.type == VideoQuality.MediaType.AUDIO }
        }
        val videoQualities = remember(state.qualities) {
            state.qualities.filter { it.type == VideoQuality.MediaType.VIDEO }
        }
        QualitySelectionSheet(
            platform = state.platform,
            audioQualities = audioQualities,
            videoQualities = videoQualities,
            isLoading = state.isLoading,
            images = state.images,
            selectedImageUrls = state.selectedImageUrls,
            sizeLoading = state.sizeLoading,
            onDownload = { quality ->
                downloadPermissionGate.launch { viewModel.onQualitySelected(quality) }
            },
            onImageToggled = viewModel::onImageSelectionToggled,
            onAllImagesToggled = viewModel::onAllImagesSelectionToggled,
            onDownloadImages = {
                downloadPermissionGate.launch(viewModel::onDownloadSelectedImages)
            },
            onCancel = {
                viewModel.onSheetDismissed()
                currentOnClose()
            },
        )
    }
}
