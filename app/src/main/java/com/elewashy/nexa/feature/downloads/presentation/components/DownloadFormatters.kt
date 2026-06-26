package com.elewashy.nexa.feature.downloads.presentation.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.ui.icons.Android
import com.elewashy.nexa.ui.icons.AudioFile
import com.elewashy.nexa.ui.icons.Description
import com.elewashy.nexa.ui.icons.FolderZip
import com.elewashy.nexa.ui.icons.Image
import com.elewashy.nexa.ui.icons.InsertDriveFile
import com.elewashy.nexa.ui.icons.VideoFile

object DownloadFormatters {

    fun formatActiveStatusPrimary(context: Context, item: DownloadItem): String {
        return when (item.status) {
            DownloadStatus.DOWNLOADING -> {
                val parts = mutableListOf<String>()
                val speed = LocalizedFormatters.speed(context, item.downloadSpeedBytesPerSecond)
                if (speed.isNotEmpty()) parts.add(speed)
                val eta = LocalizedFormatters.eta(context, item.etaSeconds)
                    .takeIf { item.status == DownloadStatus.DOWNLOADING }
                    .orEmpty()
                if (eta.isNotEmpty()) parts.add(eta)
                parts.joinToString(" · ").ifEmpty { context.getString(R.string.starting) }
            }
            DownloadStatus.PAUSED -> context.getString(R.string.paused)
            DownloadStatus.PENDING -> context.getString(R.string.waiting)
            DownloadStatus.FAILED -> context.getString(R.string.download_failed)
            else -> ""
        }
    }

    fun formatActiveStatusSecondary(context: Context, item: DownloadItem): String {
        val progress = LocalizedFormatters.percent(context, item.progress)
        return if (item.totalBytes > 0) {
            "$progress · ${LocalizedFormatters.progressSize(context, item.downloadedBytes, item.totalBytes)}"
        } else {
            progress
        }
    }

    fun formatActiveDownloadStatus(context: Context, item: DownloadItem): String {
        val primary = formatActiveStatusPrimary(context, item)
        val secondary = formatActiveStatusSecondary(context, item)
        return if (primary.isNotEmpty()) "$primary  $secondary" else secondary
    }

    fun formatCompletedDownloadStatus(context: Context, item: DownloadItem): String {
        return when (item.status) {
            DownloadStatus.COMPLETED -> {
                if (item.totalBytes > 0) {
                    LocalizedFormatters.fileSize(context, item.totalBytes)
                } else context.getString(R.string.completed)
            }
            DownloadStatus.FAILED -> context.getString(R.string.download_failed)
            DownloadStatus.CANCELLED -> context.getString(R.string.cancelled)
            else -> ""
        }
    }

    private enum class FileType { VIDEO, AUDIO, IMAGE, APK, DOCUMENT, ARCHIVE, GENERIC }

    private fun resolveFileType(item: DownloadItem): FileType {
        val mime = item.mimeType?.lowercase().orEmpty()
        val ext = item.fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") || ext in VIDEO_EXTS    -> FileType.VIDEO
            mime.startsWith("audio/") || ext in AUDIO_EXTS    -> FileType.AUDIO
            mime.startsWith("image/") || ext in IMAGE_EXTS    -> FileType.IMAGE
            mime == "application/vnd.android.package-archive" || ext == "apk" -> FileType.APK
            mime in DOCUMENT_MIMES || ext in DOCUMENT_EXTS    -> FileType.DOCUMENT
            mime in ARCHIVE_MIMES  || ext in ARCHIVE_EXTS     -> FileType.ARCHIVE
            else                                               -> FileType.GENERIC
        }
    }

    fun fileTypeIcon(item: DownloadItem): ImageVector = when (resolveFileType(item)) {
        FileType.VIDEO    -> VideoFile
        FileType.AUDIO    -> AudioFile
        FileType.IMAGE    -> Image
        FileType.APK      -> Android
        FileType.DOCUMENT -> Description
        FileType.ARCHIVE  -> FolderZip
        FileType.GENERIC  -> InsertDriveFile
    }

    fun fileTypeIconTint(item: DownloadItem, accentColor: Color): Color = when (resolveFileType(item)) {
        FileType.VIDEO    -> Color(0xFF5E81F4)
        FileType.AUDIO    -> Color(0xFFFF6B6B)
        FileType.IMAGE    -> Color(0xFF34C759)
        FileType.APK      -> Color(0xFF64B5F6)
        FileType.DOCUMENT -> Color(0xFFFFB74D)
        FileType.ARCHIVE  -> Color(0xFFAB47BC)
        FileType.GENERIC  -> accentColor
    }

    fun statusTint(status: DownloadStatus, accentColor: Color): Color = when (status) {
        DownloadStatus.FAILED    -> Color(0xFFFF3B30)
        DownloadStatus.CANCELLED -> Color(0xFF8E8E93)
        DownloadStatus.COMPLETED -> Color(0xFF34C759)
        else                     -> accentColor
    }

    fun isActiveStatus(status: DownloadStatus): Boolean = status in ACTIVE_STATUSES

    fun isCompletedStatus(status: DownloadStatus): Boolean = status in COMPLETED_STATUSES

    private val VIDEO_EXTS     = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "ts")
    private val AUDIO_EXTS     = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "opus")
    private val IMAGE_EXTS     = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic")
    private val DOCUMENT_EXTS  = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv")
    private val ARCHIVE_EXTS   = setOf("zip", "rar", "7z", "tar", "gz", "bz2")
    private val DOCUMENT_MIMES = setOf(
        "application/pdf", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain", "text/html"
    )
    private val ARCHIVE_MIMES = setOf(
        "application/zip", "application/x-rar-compressed",
        "application/x-7z-compressed", "application/x-tar", "application/gzip"
    )
    private val ACTIVE_STATUSES = setOf(
        DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED,
        DownloadStatus.PENDING, DownloadStatus.FAILED
    )
    private val COMPLETED_STATUSES = setOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED)
}
