package com.elewashy.nexa.feature.share.presentation

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.share.data.SharePlatformDetector
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ShareUiState(
    val sharedUrl: String? = null,
    val platform: String = "",
    val qualities: List<VideoQuality> = emptyList(),
    val isLoading: Boolean = false,
    val sizeLoading: Boolean = false,
    val showSheet: Boolean = false,
)

sealed interface ShareEvent {
    data class Close(val message: String? = null) : ShareEvent
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val videoExtractorRepository: VideoExtractorRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ShareEvent> = _events.asSharedFlow()

    fun handleSharedText(text: String?) {
        val url = text?.let(SharePlatformDetector::extractFirstUrl)
        if (url == null) {
            closeWithMessage(appContext.getString(R.string.no_url_found_in_shared_content))
            return
        }

        _uiState.value = ShareUiState(sharedUrl = url, isLoading = true, showSheet = true)
        viewModelScope.launch {
            try {
                val result = videoExtractorRepository.extract(url)
                if (!result.success || result.videos.isEmpty()) {
                    result.error?.let { Log.w(TAG, "Extractor failed: $it") }
                    throw IllegalStateException(appContext.getString(R.string.failed_to_extract_video))
                }

                val qualities = result.videos
                    .map { (quality, videoUrl) -> parseVideoQuality(quality, videoUrl) }
                    .sortedByDescending { it.getSortPriority() }

                _uiState.update {
                    it.copy(
                        platform = result.platform ?: PLATFORM_DEFAULT,
                        qualities = qualities,
                        isLoading = false,
                        showSheet = true,
                    )
                }

                fetchFileSizesAsync(qualities, referer = url)
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting shared video", e)
                closeWithMessage(appContext.getString(R.string.share_error, e.message ?: appContext.getString(R.string.unknown_error)))
            }
        }
    }

    private fun fetchFileSizesAsync(qualities: List<VideoQuality>, referer: String) {
        val urlsToFetch = qualities
            .filter {
                it.size == null
                        && !it.url.startsWith(PREFIX_CONVERT)
                        && it.getDisplayLabels().metadata == null
            }
            .map { it.url }
            .distinct()

        if (urlsToFetch.isEmpty()) return

        _uiState.update { it.copy(sizeLoading = true) }

        viewModelScope.launch {
            urlsToFetch.map { url ->
                async(Dispatchers.IO) {
                    url to videoExtractorRepository.fetchFileSize(url, referer)
                }
            }.forEach { deferred ->
                val (url, sizeBytes) = deferred.await()
                if (sizeBytes != null && sizeBytes > 0) {
                    val sizeText = LocalizedFormatters.fileSize(appContext, sizeBytes)
                    _uiState.update { state ->
                        state.copy(
                            qualities = state.qualities.map { q ->
                                if (q.url == url && q.size == null) q.copy(size = sizeText) else q
                            }
                        )
                    }
                }
            }
            _uiState.update { it.copy(sizeLoading = false) }
        }
    }

    fun onQualitySelected(quality: VideoQuality) {
        if (quality.url.startsWith(PREFIX_CONVERT)) {
            startConversion(quality)
        } else {
            startDownload(quality)
            closeWithMessage(appContext.getString(R.string.download_started))
        }
    }

    fun onDismiss() {
        _uiState.update { it.copy(showSheet = false) }
        emitClose()
    }

    private fun parseVideoQuality(quality: String, videoUrl: String): VideoQuality = when {
        quality.startsWith(PREFIX_AUDIO) -> VideoQuality(
            quality = quality.removePrefix(PREFIX_AUDIO),
            url = videoUrl,
            type = VideoQuality.MediaType.AUDIO,
            hasWatermark = false,
        )
        quality.startsWith(PREFIX_WATERMARK) -> VideoQuality(
            quality = quality.removePrefix(PREFIX_WATERMARK),
            url = videoUrl,
            type = VideoQuality.MediaType.VIDEO,
            hasWatermark = true,
        )
        else -> VideoQuality(
            quality = quality,
            url = videoUrl,
            type = VideoQuality.MediaType.VIDEO,
            hasWatermark = false,
        )
    }

    private fun startConversion(quality: VideoQuality) {
        val resourceContent = quality.url.removePrefix(PREFIX_CONVERT)
        val referer = uiState.value.sharedUrl
        val feedbackMessage = appContext.getString(R.string.converting_quality_message, quality.quality)

        applicationScope.launch(
            Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Conversion coroutine error", e)
            }
        ) {
            try {
                withContext(Dispatchers.Main) { showConversionNotification(quality.quality) }
                val downloadUrl = videoExtractorRepository.convertYouTubeVideo(resourceContent)
                withContext(Dispatchers.Main) {
                    cancelConversionNotification()
                    startDownload(quality.copy(url = downloadUrl), referer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed", e)
                withContext(Dispatchers.Main) {
                    cancelConversionNotification()
                    showConversionFailedNotification(quality.quality, e.message)
                }
            }
        }
        closeWithMessage(feedbackMessage)
    }

    private fun startDownload(quality: VideoQuality, referer: String? = uiState.value.sharedUrl) {
        val fileName = generateFileName(quality, referer)
        val (mimeType, forceExtension) = getFileProperties(quality)
        val intent = DownloadService.createStartIntent(
            context = appContext,
            url = quality.url,
            fileName = fileName,
            mimeType = mimeType,
            userAgent = USER_AGENT,
            referer = referer,
            origin = null,
            cookies = null,
            source = DOWNLOAD_SOURCE,
            forceExtension = forceExtension,
        )
        appContext.startForegroundService(intent)
    }

    private fun generateFileName(quality: VideoQuality, referer: String?): String {
        val platform = SharePlatformDetector.detect(referer).id
        val cleanQuality = quality.quality.replace(" ", "_")
        val extension = if (quality.type == VideoQuality.MediaType.AUDIO) EXTENSION_MP3 else EXTENSION_MP4
        return "${platform}_${cleanQuality}_${System.currentTimeMillis()}$extension"
    }

    private fun getFileProperties(quality: VideoQuality): Pair<String, String?> =
        if (quality.type == VideoQuality.MediaType.AUDIO) MIME_AUDIO_MP3 to "mp3" else MIME_VIDEO_MP4 to null

    private fun showConversionNotification(quality: String) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureConversionChannel(manager)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(appContext.getString(R.string.converting_youtube_video))
            .setContentText(appContext.getString(R.string.converting_quality_message, quality))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        manager.notify(CONVERSION_NOTIFICATION_ID, notification)
    }

    private fun cancelConversionNotification() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(CONVERSION_NOTIFICATION_ID)
    }

    private fun showConversionFailedNotification(quality: String, error: String?) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureConversionChannel(manager)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(appContext.getString(R.string.conversion_failed))
            .setContentText(appContext.getString(R.string.conversion_failed_message, quality, error ?: appContext.getString(R.string.unknown_error)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(CONVERSION_NOTIFICATION_ID, notification)
    }

    private fun ensureConversionChannel(manager: NotificationManager) {
        NotificationChannels.ensure(
            notificationManager = manager,
            id = NOTIFICATION_CHANNEL_ID,
            name = appContext.getString(R.string.youtube_conversion_channel_name),
            importance = NotificationChannels.IMPORTANCE_LOW,
            description = appContext.getString(R.string.youtube_conversion_channel_description),
            showBadge = false,
        )
    }

    private fun closeWithMessage(message: String) {
        viewModelScope.launch { _events.emit(ShareEvent.Close(message)) }
    }

    private fun emitClose() {
        viewModelScope.launch { _events.emit(ShareEvent.Close()) }
    }

    private companion object {
        const val TAG = "ShareViewModel"
        const val PLATFORM_DEFAULT = "video"
        const val PREFIX_CONVERT = "CONVERT:"
        const val PREFIX_AUDIO = "AUDIO:"
        const val PREFIX_WATERMARK = "WATERMARK:"
        const val EXTENSION_MP4 = ".mp4"
        const val EXTENSION_MP3 = ".mp3"
        const val MIME_VIDEO_MP4 = "video/mp4"
        const val MIME_AUDIO_MP3 = "audio/mpeg"
        const val DOWNLOAD_SOURCE = "SHARE_FROM_APP"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val CONVERSION_NOTIFICATION_ID = 9999
        const val NOTIFICATION_CHANNEL_ID = NotificationChannels.YOUTUBE_CONVERSION
    }
}
