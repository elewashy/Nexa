package com.elewashy.nexa.feature.downloads.data.engine

/** One worker owns each fixed byte interval. Counters/digest snapshots use the task state lock. */
data class DownloadSegment(
    val id: Int,
    val startByte: Long,
    @Volatile var endByte: Long,
    @Volatile var downloadedBytes: Long = 0,
    @Volatile var status: SegmentStatus = SegmentStatus.PENDING,
) {
    internal var digest: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
    private var checksumBytes = -1L
    private var cachedChecksum: String? = null

    internal fun checksum(): String? {
        if (checksumBytes == downloadedBytes) return cachedChecksum
        cachedChecksum = runCatching {
            val bytes = (digest.clone() as java.security.MessageDigest).digest()
            val digits = "0123456789abcdef"
            buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 255
                    append(digits[value ushr 4])
                    append(digits[value and 15])
                }
            }
        }.getOrNull()
        checksumBytes = downloadedBytes
        return cachedChecksum
    }

    val totalBytes get() = if (endByte == Long.MAX_VALUE) -1 else endByte - startByte + 1
    val currentOffset get() = startByte + downloadedBytes
    val hasRemainingBytes get() = endByte == Long.MAX_VALUE || downloadedBytes < totalBytes
    val remainingBytes get() = if (endByte == Long.MAX_VALUE) Long.MAX_VALUE else totalBytes - downloadedBytes
    val effectiveDownloadedBytes get() = if (totalBytes >= 0) minOf(downloadedBytes, totalBytes) else downloadedBytes
}

enum class SegmentStatus { PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED }
