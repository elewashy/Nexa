package com.elewashy.nexa.feature.downloads.data.persistence

/**
 * Persisted byte-range state of a single download segment.
 *
 * Segments complete out of order; resume after process death must NOT assume
 * bytes [0..downloadedBytes] are contiguous — each segment's own offset/length/
 * progress is restored and verified against the `.part` file instead.
 * Shared contract between the download engine and every persistence backend.
 */
data class PersistedSegment(
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long,
    val completed: Boolean
)
