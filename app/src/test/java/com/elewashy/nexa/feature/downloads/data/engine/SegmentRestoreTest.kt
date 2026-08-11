package com.elewashy.nexa.feature.downloads.data.engine

import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure segment-restore verification in
 * [DownloadTask.Companion.rebuildSegmentsFromState]: contiguous coverage is
 * accepted, any hole/overlap/length/progress inconsistency forces a restart
 * (null).
 */
class SegmentRestoreTest {

    private fun seg(start: Long, end: Long, downloaded: Long, completed: Boolean = false) =
        PersistedSegment(startByte = start, endByte = end, downloadedBytes = downloaded, completed = completed)

    // ── Accept cases ────────────────────────────────────────────────────

    @Test
    fun `contiguous coverage is accepted and mapped`() {
        val rebuilt = DownloadTask.rebuildSegmentsFromState(
            restoredSegments = listOf(
                seg(0, 49, downloaded = 50, completed = true),
                seg(50, 99, downloaded = 20)
            ),
            totalBytes = 100
        )!!

        assertEquals(2, rebuilt.size)

        assertEquals(0, rebuilt[0].id)
        assertEquals(0L, rebuilt[0].startByte)
        assertEquals(49L, rebuilt[0].endByte)
        assertEquals(50L, rebuilt[0].downloadedBytes)
        assertEquals(SegmentStatus.COMPLETED, rebuilt[0].status)

        assertEquals(1, rebuilt[1].id)
        assertEquals(50L, rebuilt[1].startByte)
        assertEquals(99L, rebuilt[1].endByte)
        assertEquals(20L, rebuilt[1].downloadedBytes)
        assertEquals(SegmentStatus.PENDING, rebuilt[1].status)
        assertEquals(70L, rebuilt[1].currentOffset)
    }

    @Test
    fun `unsorted persisted segments are reordered`() {
        val rebuilt = DownloadTask.rebuildSegmentsFromState(
            restoredSegments = listOf(
                seg(50, 99, downloaded = 0),
                seg(0, 49, downloaded = 50, completed = true)
            ),
            totalBytes = 100
        )!!

        assertEquals(0L, rebuilt[0].startByte)
        assertEquals(50L, rebuilt[1].startByte)
        assertEquals(0, rebuilt[0].id)
        assertEquals(1, rebuilt[1].id)
    }

    @Test
    fun `single segment covering the whole file is accepted`() {
        val rebuilt = DownloadTask.rebuildSegmentsFromState(
            restoredSegments = listOf(seg(0, 999, downloaded = 1000)),
            totalBytes = 1000
        )!!

        assertEquals(1, rebuilt.size)
        assertEquals(SegmentStatus.COMPLETED, rebuilt[0].status)
        assertEquals(1000L, rebuilt[0].downloadedBytes)
    }

    @Test
    fun `full downloadedBytes counts as completed even without the flag`() {
        val rebuilt = DownloadTask.rebuildSegmentsFromState(
            restoredSegments = listOf(seg(0, 99, downloaded = 100, completed = false)),
            totalBytes = 100
        )!!

        assertEquals(SegmentStatus.COMPLETED, rebuilt[0].status)
        assertEquals(100L, rebuilt[0].downloadedBytes)
    }

    // ── Reject cases (null forces a clean restart) ─────────────────────

    @Test
    fun `empty state or unknown size is rejected`() {
        assertNull(DownloadTask.rebuildSegmentsFromState(emptyList(), totalBytes = 100))
        assertNull(DownloadTask.rebuildSegmentsFromState(listOf(seg(0, 99, 0)), totalBytes = 0))
        assertNull(DownloadTask.rebuildSegmentsFromState(listOf(seg(0, 99, 0)), totalBytes = -1))
    }

    @Test
    fun `hole between segments is rejected`() {
        val rebuilt = DownloadTask.rebuildSegmentsFromState(
            restoredSegments = listOf(seg(0, 39, 40, completed = true), seg(50, 99, 0)),
            totalBytes = 100
        )
        assertNull(rebuilt)
    }

    @Test
    fun `overlapping segments are rejected`() {
        val rebuilt = DownloadTask.rebuildSegmentsFromState(
            restoredSegments = listOf(seg(0, 50, 51, completed = true), seg(50, 99, 0)),
            totalBytes = 100
        )
        assertNull(rebuilt)
    }

    @Test
    fun `coverage not starting at zero is rejected`() {
        assertNull(
            DownloadTask.rebuildSegmentsFromState(listOf(seg(10, 99, 0)), totalBytes = 100)
        )
    }

    @Test
    fun `coverage not reaching the end is rejected`() {
        assertNull(
            DownloadTask.rebuildSegmentsFromState(listOf(seg(0, 89, 90, completed = true)), totalBytes = 100)
        )
    }

    @Test
    fun `segments beyond the file end are rejected`() {
        assertNull(
            DownloadTask.rebuildSegmentsFromState(listOf(seg(0, 100, 101, completed = true)), totalBytes = 100)
        )
    }

    @Test
    fun `unknown size sentinel endByte is rejected`() {
        assertNull(
            DownloadTask.rebuildSegmentsFromState(listOf(seg(0, Long.MAX_VALUE, 0)), totalBytes = 100)
        )
    }

    @Test
    fun `impossible progress counters are rejected`() {
        // More downloaded bytes than the segment covers.
        assertNull(
            DownloadTask.rebuildSegmentsFromState(listOf(seg(0, 99, 101)), totalBytes = 100)
        )
        // Negative progress.
        assertNull(
            DownloadTask.rebuildSegmentsFromState(listOf(seg(0, 99, -1)), totalBytes = 100)
        )
    }
}
