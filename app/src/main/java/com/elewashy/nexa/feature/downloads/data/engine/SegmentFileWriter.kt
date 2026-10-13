package com.elewashy.nexa.feature.downloads.data.engine

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional writes never share a mutable file offset. Close only after workers have joined. */
class SegmentFileWriter(private val filePath: String, private val totalSize: Long) {
    private val lock = Any()
    @Volatile private var channel: FileChannel? = null
    @Volatile private var closed = false

    /** Sparse sizing, not physical allocation or a guarantee of sufficient free space. */
    fun preAllocate() {
        if (totalSize < 0) return
        synchronized(lock) {
            check(channel == null) { "Output is already open" }
            check(!closed) { "Output is closed" }
            val file = File(filePath)
            file.parentFile?.mkdirs()
            val output = RandomAccessFile(file, "rw")
            try {
                output.setLength(totalSize)
                channel = output.channel
            } catch (error: Exception) {
                output.close()
                throw error
            }
        }
    }

    fun writeAt(position: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) =
        writeAt(position, ByteBuffer.wrap(data, offset, length))

    internal fun writeAt(position: Long, buffer: ByteBuffer) {
        val output = ensureOpen()
        var offset = position
        var stalled = 0
        while (buffer.hasRemaining()) {
            val count = output.write(buffer, offset)
            if (count <= 0) {
                if (++stalled >= 3) throw IOException("Unable to write download output")
                Thread.yield()
            } else {
                offset += count
                stalled = 0
            }
        }
    }

    fun force() = ensureOpen().force(true)

    fun close() = synchronized(lock) {
        closed = true
        val output = channel
        channel = null
        output?.close()
    }

    private fun ensureOpen(): FileChannel {
        if (closed) throw IOException("Download output is closed")
        channel?.let { return it }
        return synchronized(lock) {
            if (closed) throw IOException("Download output is closed")
            channel ?: run {
                val file = File(filePath)
                file.parentFile?.mkdirs()
                RandomAccessFile(file, "rw").channel.also { channel = it }
            }
        }
    }
}
