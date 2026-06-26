package com.elewashy.nexa.feature.downloads.data.engine

/**
 * Represents a single byte-range segment of a file download.
 *
 * Each segment downloads a contiguous portion of the file [startByte]..[endByte]
 * and writes it directly to the correct file position using RandomAccessFile.
 *
 * Mutable fields are updated by [SegmentDownloader] during execution.
 * Thread-safety: each segment is owned by exactly one coroutine at a time.
 *
 * @property id         Unique segment identifier within the parent [DownloadTask]
 * @property startByte  First byte to download (inclusive)
 * @property endByte    Last byte to download (inclusive). Mutable to support
 *                      dynamic segment splitting — when a segment is split,
 *                      we shrink the existing segment's end and create a new one.
 */
data class DownloadSegment(
    val id: Int,
    val startByte: Long,

    /** Mutable — adjusted during dynamic segment splitting. */
    @Volatile var endByte: Long,

    /** Number of bytes successfully written for this segment. */
    @Volatile var downloadedBytes: Long = 0L,

    /** Current state of this segment. */
    @Volatile var status: SegmentStatus = SegmentStatus.PENDING,

    /** Number of retries attempted so far. */
    var retryCount: Int = 0
) {
    /** Total number of bytes this segment is responsible for. Returns -1 if unknown size. */
    val totalBytes: Long get() = if (endByte == Long.MAX_VALUE) -1L else (endByte - startByte + 1)

    /** The next byte that needs to be downloaded (resume point). */
    val currentOffset: Long get() = startByte + downloadedBytes

    /** Whether there are still bytes left to download. */
    val hasRemainingBytes: Boolean get() = if (endByte == Long.MAX_VALUE) true else (downloadedBytes < totalBytes)

    /** Number of bytes remaining to download. */
    val remainingBytes: Long get() = if (endByte == Long.MAX_VALUE) Long.MAX_VALUE else (totalBytes - downloadedBytes)

    /** 
     * Downloaded bytes clamped to totalBytes, preventing visual progress from 
     * exceeding 100% when active splitting briefly races with ongoing reads.
     */
    val effectiveDownloadedBytes: Long get() {
        return if (totalBytes > 0) java.lang.Math.min(downloadedBytes, totalBytes) else downloadedBytes
    }

    /** Progress as a fraction 0.0..1.0 */
    val progressFraction: Double
        get() {
            val total = totalBytes
            return if (total > 0) effectiveDownloadedBytes.toDouble() / total else 0.0
        }
}

/**
 * Possible states for a download segment.
 */
enum class SegmentStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}
