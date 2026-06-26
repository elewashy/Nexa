package com.elewashy.nexa.feature.downloads.domain.model

/**
 * Represents the possible states of a download task.
 */
enum class DownloadStatus {
    PENDING,      // Waiting in the queue to start
    DOWNLOADING,  // Actively downloading data
    PAUSED,       // Download paused by user or system
    COMPLETED,    // Download finished successfully
    FAILED,       // Download failed due to an error
    CANCELLED     // Download cancelled by user
}
