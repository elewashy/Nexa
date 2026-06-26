package com.elewashy.nexa.feature.downloads.domain.model

/**
 * Parameters for starting a new download.
 *
 * Field set mirrors the Intent extras consumed by `DownloadService.handleStartDownload`
 * to preserve behavior exactly during the Phase 3 refactor.
 */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val userAgent: String?,
    val referer: String?,
    val origin: String?,
    val cookies: String?,
    val source: String,
    val forceExtension: String? = null
)
