package com.elewashy.nexa.feature.downloads.presentation.components

import androidx.annotation.StringRes
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask

@StringRes
internal fun downloadErrorMessageResource(error: String?): Int = when (error) {
    DownloadTask.ERROR_FILE_MISSING -> R.string.downloaded_file_missing
    DownloadTask.ERROR_LINK_REFRESH_FAILED -> R.string.download_link_refresh_failed
    DownloadTask.ERROR_SOURCE_UNAVAILABLE -> R.string.download_source_unavailable
    DownloadTask.ERROR_RESUME_URL_DEAD -> R.string.download_resume_url_dead
    DownloadTask.ERROR_STORAGE_PERMISSION -> R.string.storage_permission_required_downloads
    DownloadTask.ERROR_UNSAFE_RESUME -> R.string.download_resume_unsafe
    DownloadTask.ERROR_FINALIZE_FAILED -> R.string.download_finalize_failed
    DownloadTask.ERROR_RESUME_UNREACHABLE -> R.string.download_resume_unreachable
    else -> R.string.download_failed
}
