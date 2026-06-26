package com.elewashy.nexa.feature.downloads.data.engine

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Thread-safe file writer for segmented downloads.
 *
 * Uses [RandomAccessFile] to write bytes at arbitrary positions, enabling
 * multiple [SegmentDownloader]s to write their data concurrently without
 * corruption.
 *
 * Design:
 *  - Uses [FileChannel] to write bytes at specific positions. [FileChannel]
 *    is natively thread-safe for writing at an absolute position via
 *    [FileChannel.write(ByteBuffer, Long)], allowing multiple segments
 *    to write concurrently without a lock bottleneck.
 *  - The file is pre-allocated on creation for:
 *      (a) fast-fail if disk space is insufficient
 *      (b) reduced filesystem fragmentation
 *      (c) contiguous sector allocation for better throughput on eMMC/UFS
 *
 * Performance note:
 *  Using `synchronized` on RAF is much cheaper than a coroutine [Mutex]
 *  for high-frequency I/O. The lock granularity is minimal (just seek+write),
 *  so segments rarely contend — they're writing to different file positions.
 *
 * @property filePath   Absolute path where the file will be written.
 * @property totalSize  Expected file size in bytes (used for pre-allocation).
 *                      Pass -1 to disable pre-allocation (single-stream mode).
 */
class SegmentFileWriter(
    private val filePath: String,
    private val totalSize: Long
) {
    companion object {
        private const val TAG = "SegmentFileWriter"
    }

    /** Lock object for init/close operations. */
    private val initLock = Any()

    /** Lazy-initialized Channel; opened on first write, closed via [close]. */
    @Volatile private var channel: FileChannel? = null

    /**
     * Pre-allocates the file to [totalSize] bytes.
     *
     * Call this **once** before any segment downloading starts.
     * On Android's UFS/eMMC storage this significantly improves sequential
     * write throughput and prevents fragmentation.
     *
     * @throws java.io.IOException if there isn't enough disk space.
     */
    fun preAllocate() {
        if (totalSize <= 0) return

        synchronized(initLock) {
            try {
                val file = File(filePath)
                file.parentFile?.mkdirs()

                val randomAccessFile = RandomAccessFile(file, "rw")
                randomAccessFile.setLength(totalSize)
                channel = randomAccessFile.channel

                Log.d(TAG, "Pre-allocated ${totalSize} bytes → $filePath")
            } catch (e: Exception) {
                Log.e(TAG, "Pre-allocation failed: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Writes [data] at [position] in the file.
     *
     * This method is **thread-safe** — multiple threads/coroutines can call it
     * concurrently. The synchronized block on the RAF instance ensures that
     * seek+write is atomic (no interleaving from other segments).
     *
     * The lock is very lightweight: held only for the ~32KB buffer write
     * (microseconds on modern flash storage). Segments writing to different
     * file positions will rarely contend.
     *
     * @param position  File offset (byte position) to write at.
     * @param data      Byte array containing data to write.
     * @param offset    Start offset within [data].
     * @param length    Number of bytes to write from [data].
     */
    fun writeAt(position: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val fc = ensureOpen()
        // FileChannel.write(ByteBuffer, Long) is thread-safe and doesn't update the 
        // channel's position, eliminating the need for seek+synchronization.
        val buffer = ByteBuffer.wrap(data, offset, length)
        var writePosition = position
        var zeroWriteCount = 0
        while (buffer.hasRemaining()) {
            val written = fc.write(buffer, writePosition)
            if (written < 0) {
                throw java.io.EOFException("FileChannel closed while writing $filePath")
            }
            if (written == 0) {
                zeroWriteCount++
                if (zeroWriteCount >= 3) {
                    throw java.io.IOException("Unable to make progress writing $filePath")
                }
                Thread.yield()
                continue
            }
            zeroWriteCount = 0
            writePosition += written
        }
    }

    /**
     * Closes the underlying file handle.
     * Safe to call multiple times. Should only be called after all segment
     * coroutines have completed.
     */
    fun close() {
        synchronized(initLock) {
            try {
                channel?.close()
                channel = null
                Log.d(TAG, "Closed file writer: $filePath")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing file writer: ${e.message}")
            }
        }
    }

    /**
     * Deletes the file from disk. Used when a download is cancelled.
     */
    fun deleteFile() {
        close()
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Deleted: $filePath")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting file: ${e.message}")
        }
    }

    /**
     * Ensures the [RandomAccessFile] is open; creates it on first call.
     * Thread-safe via the double-checked locking pattern on [initLock].
     */
    private fun ensureOpen(): FileChannel {
        channel?.let { return it }

        synchronized(initLock) {
            channel?.let { return it }

            val file = File(filePath)
            file.parentFile?.mkdirs()
            val newRaf = RandomAccessFile(file, "rw")
            val newChannel = newRaf.channel
            channel = newChannel
            return newChannel
        }
    }
}
