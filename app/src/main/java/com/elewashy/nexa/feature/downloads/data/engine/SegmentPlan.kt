package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log

/**
 * Splits a file into download segments and handles dynamic re-segmentation
 * when a segment finishes early (stealing remaining bytes from the slowest
 * segment to maximize throughput).
 *
 * Segment count is determined by file size:
 *  - < 1 MB  → 1 segment  (overhead of multiple connections not worth it)
 *  - < 10 MB → 2 segments
 *  - < 50 MB → 4 segments
 *  - < 200 MB → 6 segments
 *  - ≥ 200 MB → 8 segments
 *
 * All methods are pure functions — no mutable state.
 */
object SegmentPlan {

    private const val TAG = "SegmentPlan"

    /** Minimum segment size — below this, splitting is counter-productive. */
    private const val MIN_SEGMENT_SIZE = 256 * 1024L  // 256 KB

    /** Minimum remaining bytes in a segment to justify splitting it. */
    private const val MIN_SPLIT_REMAINING = 512 * 1024L  // 512 KB

    /**
     * Creates the initial segment list for a file of [totalSize] bytes.
     *
     * @param totalSize  File size in bytes (must be > 0).
     * @return List of [DownloadSegment] covering the entire file.
     */
    fun createSegments(totalSize: Long): List<DownloadSegment> {
        require(totalSize > 0) { "totalSize must be > 0, got $totalSize" }

        val segmentCount = optimalSegmentCount(totalSize)
        val segmentSize = totalSize / segmentCount
        val segments = mutableListOf<DownloadSegment>()

        for (i in 0 until segmentCount) {
            val start = i * segmentSize
            // Last segment takes any remainder bytes
            val end = if (i == segmentCount - 1) totalSize - 1 else (start + segmentSize - 1)

            segments.add(
                DownloadSegment(
                    id = i,
                    startByte = start,
                    endByte = end
                )
            )
        }

        Log.d(TAG, "Created $segmentCount segments for ${totalSize / 1024}KB file")
        return segments
    }

    /**
     * Creates multiple segments for a specific byte range.
     * Used for resuming after app restart where only partial data remains.
     *
     * @param rangeStart  First byte to download (inclusive).
     * @param rangeEnd    Last byte to download (inclusive).
     * @return List of [DownloadSegment] covering [rangeStart]..[rangeEnd].
     */
    fun createSegmentsForRange(rangeStart: Long, rangeEnd: Long): List<DownloadSegment> {
        val rangeSize = rangeEnd - rangeStart + 1
        require(rangeSize > 0) { "Invalid range: $rangeStart..$rangeEnd" }

        val segmentCount = optimalSegmentCount(rangeSize)
        val segmentSize = rangeSize / segmentCount
        val segments = mutableListOf<DownloadSegment>()

        for (i in 0 until segmentCount) {
            val start = rangeStart + i * segmentSize
            val end = if (i == segmentCount - 1) rangeEnd else (start + segmentSize - 1)

            segments.add(
                DownloadSegment(
                    id = i,
                    startByte = start,
                    endByte = end
                )
            )
        }

        Log.d(TAG, "Created $segmentCount resume segments for " +
                "${rangeSize / 1024}KB range [$rangeStart-$rangeEnd]")
        return segments
    }

    /**
     * Creates a single segment covering the entire file.
     * Used when the server doesn't support byte-range requests.
     */
    fun createSingleSegment(totalSize: Long): List<DownloadSegment> {
        val endByte = if (totalSize > 0) totalSize - 1 else Long.MAX_VALUE
        return listOf(
            DownloadSegment(
                id = 0,
                startByte = 0,
                endByte = endByte
            )
        )
    }

    /**
     * Attempts to create a dynamic segment by splitting the segment with the
     * most remaining bytes.
     *
     * Called when a segment finishes early to redistribute work across
     * available connections and maximize download throughput.
     *
     * @param activeSegments  Currently downloading segments.
     * @param nextSegmentId   ID to assign to the new segment.
     * @return A pair of (modified existing segment, new segment), or null
     *         if no segment has enough remaining bytes to justify splitting.
     */
    fun trySplitLargestSegment(
        activeSegments: List<DownloadSegment>,
        nextSegmentId: Int
    ): Pair<DownloadSegment, DownloadSegment>? {

        // Find the segment with the most remaining bytes
        val candidate = activeSegments
            .filter { it.status == SegmentStatus.DOWNLOADING && it.remainingBytes > MIN_SPLIT_REMAINING }
            .maxByOrNull { it.remainingBytes }
            ?: return null

        val currentOffset = candidate.currentOffset
        val remaining = candidate.remainingBytes
        val splitPoint = currentOffset + remaining / 2
        val oldEndByte = candidate.endByte

        // CRITICAL: Shrink the existing segment's endByte BEFORE creating the new one.
        // This ensures non-overlapping byte ranges:
        //   Existing segment: [currentOffset .. splitPoint-1]
        //   New segment:      [splitPoint   .. oldEndByte]
        // The SegmentDownloader reads segment.remainingBytes on every buffer read,
        // so shrinking endByte will make the existing downloader stop at the new boundary.
        candidate.endByte = splitPoint - 1

        val newSegment = DownloadSegment(
            id = nextSegmentId,
            startByte = splitPoint,
            endByte = oldEndByte
        )

        Log.d(TAG, "Dynamic split: segment ${candidate.id} " +
                "shrunk to [${candidate.currentOffset}-${candidate.endByte}] " +
                "(${candidate.remainingBytes / 1024}KB) → new segment $nextSegmentId " +
                "[$splitPoint-$oldEndByte] (${newSegment.totalBytes / 1024}KB)")

        return Pair(candidate, newSegment)
    }

    /**
     * Determines the optimal number of segments based on file size.
     */
    private fun optimalSegmentCount(totalSize: Long): Int {
        val count = when {
            totalSize < 1 * 1024 * 1024       -> 1   // < 1 MB
            totalSize < 10 * 1024 * 1024      -> 2   // < 10 MB
            totalSize < 50 * 1024 * 1024      -> 4   // < 50 MB
            totalSize < 200L * 1024 * 1024    -> 6   // < 200 MB
            totalSize < 500L * 1024 * 1024    -> 8   // < 500 MB
            totalSize < 1024L * 1024 * 1024   -> 12  // < 1 GB
            else                               -> 16  // ≥ 1 GB
        }

        // Ensure each segment is at least MIN_SEGMENT_SIZE
        val maxPossible = (totalSize / MIN_SEGMENT_SIZE).toInt().coerceAtLeast(1)
        return count.coerceAtMost(maxPossible)
    }
}
