package com.elewashy.nexa.feature.share.presentation

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.share.data.SharePlatformDetector
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
            } catch (e: CancellationException) {
                throw e
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
                        && !it.url.startsWith(MediaLabel.CONVERT_PREFIX)
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
        if (quality.url.startsWith(MediaLabel.CONVERT_PREFIX)) {
            startConversion(quality)
        } else {
            // Only claim "Download started" when the service really started —
            // otherwise the tap-to-download fallback notification is the signal.
            if (startDownload(quality)) {
                closeWithMessage(appContext.getString(R.string.download_started))
            } else {
                // Fallback notification posted — tell the user the download is
                // queued and how to start it.
                closeWithMessage(appContext.getString(R.string.download_queued_tap_to_start))
            }
        }
    }

    fun onDismiss() {
        _uiState.update { it.copy(showSheet = false) }
        emitClose()
    }

    private fun parseVideoQuality(rawLabel: String, videoUrl: String): VideoQuality {
        val (kind, label) = MediaLabel.parse(rawLabel)
        return when (kind) {
            MediaLabel.Kind.AUDIO -> VideoQuality(
                quality = label,
                url = videoUrl,
                type = VideoQuality.MediaType.AUDIO,
                hasWatermark = false,
            )
            MediaLabel.Kind.WATERMARKED_VIDEO -> VideoQuality(
                quality = label,
                url = videoUrl,
                type = VideoQuality.MediaType.VIDEO,
                hasWatermark = true,
            )
            MediaLabel.Kind.CONVERSION,
            MediaLabel.Kind.VIDEO -> VideoQuality(
                quality = label,
                url = videoUrl,
                type = VideoQuality.MediaType.VIDEO,
                hasWatermark = false,
            )
        }
    }

    private fun startConversion(quality: VideoQuality) {
        val resourceContent = quality.url.removePrefix(MediaLabel.CONVERT_PREFIX)
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
            } catch (cancellation: CancellationException) {
                throw cancellation
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

    /**
     * Returns `true` when the download service actually started. `false` means
     * the start was refused and the tap-to-download fallback notification was
     * posted instead — callers must not claim the download is running.
     */
    private fun startDownload(quality: VideoQuality, referer: String? = uiState.value.sharedUrl): Boolean {
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
        return try {
            appContext.startForegroundService(intent)
            true
        } catch (e: Exception) {
            // ANY failure to start the service (most commonly Android 12+
            // refusing startForegroundService from the background) must not
            // drop the URL — offer it as a tap-to-download notification instead.
            Log.e(TAG, "startForegroundService failed", e)
            postTapToDownloadNotification(intent, fileName)
            false
        }
    }

    /**
     * Fallback when the foreground download service cannot be started from the
     * background: leave the URL as a notification the user can tap to start the
     * download — a notification tap is an FGS-start exemption. Reuses the
     * downloads notification channel.
     */
    // The whole notification is intentionally the user gesture that starts
    // DownloadService; this is not an Activity launch and therefore does not
    // belong in a secondary action button.
    @SuppressLint("LaunchActivityFromNotification")
    private fun postTapToDownloadNotification(startIntent: Intent, fileName: String) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            Log.w(TAG, "Cannot offer tap-to-download fallback — notifications are disabled")
            return
        }
        try {
            NotificationChannels.ensure(
                notificationManager = manager,
                id = NotificationChannels.DOWNLOADS,
                name = appContext.getString(R.string.download_notification_channel_name),
                importance = NotificationChannels.IMPORTANCE_LOW,
                description = appContext.getString(R.string.download_notification_channel_description),
                showBadge = false,
            )
            // Stable bounded code (no timestamp seeding — see downloads notification
            // codes). Offset past CONVERSION_NOTIFICATION_ID AND above
            // DownloadNotificationManager's item-code range (0..899_999) so the
            // fallback notifications can never collide with per-item codes.
            val requestCode =
                ((fileName.hashCode() and 0x7FFFFFFF) % FALLBACK_NOTIFICATION_ID_RANGE) +
                    FALLBACK_NOTIFICATION_ID_BASE
            val notification = NotificationCompat.Builder(appContext, NotificationChannels.DOWNLOADS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(fileName)
                .setContentText(appContext.getString(R.string.tap_to_start_download))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getService(
                        appContext,
                        requestCode,
                        startIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
            manager.notify(requestCode, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post tap-to-download notification", e)
        }
    }

    private fun generateFileName(quality: VideoQuality, referer: String?): String {
        val platform = uiState.value.platform.ifBlank { PLATFORM_DEFAULT }
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
        const val EXTENSION_MP4 = ".mp4"
        const val EXTENSION_MP3 = ".mp3"
        const val MIME_VIDEO_MP4 = "video/mp4"
        const val MIME_AUDIO_MP3 = "audio/mpeg"
        const val DOWNLOAD_SOURCE = "SHARE_FROM_APP"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val CONVERSION_NOTIFICATION_ID = 9999
        const val NOTIFICATION_CHANNEL_ID = NotificationChannels.YOUTUBE_CONVERSION

        /**
         * Tap-to-download fallback notification IDs: above the download
         * notification manager's item-code range (0..899_999) and below its
         * group summary ID (999_999) so they can collide with neither.
         */
        const val FALLBACK_NOTIFICATION_ID_BASE = 910_000
        // Kept strictly below 999_999 — that ID is the downloads group summary.
        const val FALLBACK_NOTIFICATION_ID_RANGE = 89_000
    }
}
