package com.elewashy.nexa.feature.downloads.data.engine

/** Fixed disjoint ranges avoid per-buffer splitting locks and unbounded request churn. */
object SegmentPlan {
    fun createSegments(totalSize: Long): List<DownloadSegment> {
        require(totalSize > 0)
        val count = when {
            totalSize < 1024 * 1024 -> 1
            totalSize < 10 * 1024 * 1024 -> 2
            totalSize < 50 * 1024 * 1024 -> 4
            else -> 8
        }
        val size = totalSize / count
        return List(count) { index ->
            val start = index * size
            DownloadSegment(index, start, if (index == count - 1) totalSize - 1 else start + size - 1)
        }
    }

    fun createSingleSegment(totalSize: Long): List<DownloadSegment> =
        listOf(DownloadSegment(0, 0, if (totalSize >= 0) totalSize - 1 else Long.MAX_VALUE))
}
