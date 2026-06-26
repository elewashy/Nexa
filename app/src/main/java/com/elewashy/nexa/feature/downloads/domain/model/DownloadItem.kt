package com.elewashy.nexa.feature.downloads.domain.model

data class DownloadItem(
    val id: Long, // Unique ID assigned by the service (e.g., timestamp or counter)
    val url: String,
    var fileName: String, // Suggested filename, can be updated by service
    var filePath: String, // Full path where the file is/will be saved, can be updated
    var totalBytes: Long = -1, // Total size of the file in bytes, -1 if unknown
    var downloadedBytes: Long = 0, // Bytes downloaded so far
    var status: DownloadStatus = DownloadStatus.PENDING,
    val mimeType: String? = null, // MIME type of the file
    // Headers needed for the download request
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val cookies: String? = null,
    val source: String = "UNKNOWN", // Source of the download (e.g., "JSON", "BROWSER")
    val createdAt: Long = System.currentTimeMillis(), // Timestamp when created
    var failureCount: Int = 0, // Number of times this download has failed
    var downloadSpeedBytesPerSecond: Long = 0, // Current download speed in bytes per second
    var etaSeconds: Long = -1, // Estimated time remaining in seconds, -1 if unknown
    var wasWaitingForNetwork: Boolean = false // Flag to track if download was paused due to network loss
) {
    val progress: Int
        get() = when {
            status == DownloadStatus.COMPLETED -> 100
            totalBytes > 0 && downloadedBytes <= totalBytes -> ((downloadedBytes * 100) / totalBytes).toInt()
            else -> 0
        }
    
}
