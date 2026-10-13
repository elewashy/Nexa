package com.elewashy.nexa.feature.downloads.data.engine

import com.elewashy.nexa.feature.downloads.data.persistence.PersistedSegment
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadMetadata
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class DownloadTaskIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = ByteArray(2 * 1024 * 1024) { (it * 31).toByte() }
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun server(ignoreRange: Boolean = false, changed: Boolean = false, unknown: Boolean = false, slow: Boolean = false): Pair<HttpServer, MutableList<String>> {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests.add("${exchange.requestMethod}:${range.orEmpty()}")
            exchange.responseHeaders.add("ETag", if (changed) "\"v2\"" else "\"v1\"")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"My File.BIN\"")
            val matched = range?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
            val start = if (matched != null && !ignoreRange && !changed) matched.groupValues[1].toInt() else 0
            val end = if (matched != null && !ignoreRange && !changed) matched.groupValues[2].toInt() else payload.lastIndex
            val partial = matched != null && !ignoreRange && !changed
            if (partial) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            exchange.sendResponseHeaders(if (partial) 206 else 200, if (unknown) 0 else (end - start + 1).toLong())
            try {
                exchange.responseBody.use { stream ->
                    if (!slow) stream.write(payload, start, end - start + 1)
                    else {
                        var position = start
                        while (position <= end) {
                            val count = minOf(16 * 1024, end - position + 1)
                            stream.write(payload, position, count)
                            stream.flush()
                            position += count
                            Thread.sleep(10)
                        }
                    }
                }
            }
            catch (_: java.io.IOException) { }
            exchange.close()
        }
        server.start()
        return server to requests
    }

    private suspend fun runTask(item: DownloadItem, states: List<PersistedSegment> = emptyList()): DownloadStatus = coroutineScope {
        val done = CompletableDeferred<DownloadStatus>()
        val task = DownloadTask(item, OkHttpClient(), restoredSegments = states,
            onStatusChange = { _, status ->
                if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED) done.complete(status)
            })
        task.start(this)
        withTimeout(15_000) { done.await() }
    }

    @Test fun `fresh parallel download reuses initial GET and is byte exact`() = runBlocking {
        val (server, requests) = server()
        try {
            val output = File(temp.newFolder(), "file")
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path)
            assertEquals(DownloadStatus.COMPLETED, runTask(item))
            assertArrayEquals(payload, output.readBytes())
            assertTrue(requests.size in 1..3)
            assertEquals("GET:", requests.first())
            assertEquals(payload.size.toLong(), item.downloadedBytes)
            assertFalse(File(output.path + ".part").exists())
        } finally { server.stop(0) }
    }

    @Test fun `restart resumes disjoint ranges without treating holes as downloaded`() = runBlocking {
        val (server, requests) = server()
        try {
            val output = File(temp.newFolder(), "file")
            val half = payload.size / 2
            java.io.RandomAccessFile(output.path + ".part", "rw").use {
                it.setLength(payload.size.toLong())
                it.write(payload, 0, 1000)
                it.seek(half.toLong())
                it.write(payload, half, 2000)
            }
            val states = listOf(PersistedSegment(0, half - 1L, 1000, false, hash(payload.copyOf(1000))),
                PersistedSegment(half.toLong(), payload.lastIndex.toLong(), 2000, false, hash(payload.copyOfRange(half, half + 2000))))
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path,
                totalBytes = payload.size.toLong(), downloadedBytes = 3000,
                metadata = DownloadMetadata(etag = "\"v1\""))
            assertEquals(DownloadStatus.COMPLETED, runTask(item, states))
            assertArrayEquals(payload, output.readBytes())
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.startsWith("GET:bytes=") })
        } finally { server.stop(0) }
    }

    @Test fun `changed representation preserves existing partial bytes`() = runBlocking {
        val (server, _) = server(changed = true)
        try {
            val output = File(temp.newFolder(), "file")
            val partial = File(output.path + ".part")
            partial.writeBytes(payload.copyOf(1000))
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path,
                totalBytes = payload.size.toLong(), downloadedBytes = 1000, metadata = DownloadMetadata(etag = "\"v1\""))
            assertEquals(DownloadStatus.FAILED, runTask(item, listOf(PersistedSegment(0, payload.lastIndex.toLong(), 1000, false, hash(payload.copyOf(1000))))))
            assertEquals(DownloadTask.ERROR_UNSAFE_RESUME, item.errorMessage)
            assertArrayEquals(payload.copyOf(1000), partial.readBytes())
            assertFalse(output.exists())
        } finally { server.stop(0) }
    }

    @Test fun `chunked response and empty output complete correctly`() = runBlocking {
        val (server, requests) = server(unknown = true)
        try {
            val output = File(temp.newFolder(), "file")
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path)
            assertEquals(DownloadStatus.COMPLETED, runTask(item))
            assertArrayEquals(payload, output.readBytes())
            assertEquals(1, requests.size)
        } finally { server.stop(0) }
    }

    @Test fun `range advertisement lies fall back using original GET`() = runBlocking {
        val (server, requests) = server(ignoreRange = true)
        try {
            val output = File(temp.newFolder(), "file")
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path)
            assertEquals(DownloadStatus.COMPLETED, runTask(item))
            assertArrayEquals(payload, output.readBytes())
            assertTrue(requests.size in 1..3)
        } finally { server.stop(0) }
    }

    @Test fun `corrupted partial file is rejected without network or deletion`() = runBlocking {
        val output = File(temp.newFolder(), "file")
        val partial = File(output.path + ".part")
        partial.writeBytes(byteArrayOf(9, 9, 9))
        val item = DownloadItem(1, "http://127.0.0.1:1/file", "file", output.path, totalBytes = 100, downloadedBytes = 3,
            metadata = DownloadMetadata(etag = "\"v1\""))
        assertEquals(DownloadStatus.FAILED, runTask(item, listOf(PersistedSegment(0, 99, 3, false, hash(byteArrayOf(1, 2, 3))))))
        assertEquals(DownloadTask.ERROR_UNSAFE_RESUME, item.errorMessage)
        assertArrayEquals(byteArrayOf(9, 9, 9), partial.readBytes())
    }

    @Test fun `rapid pause resume joins old writers and preserves byte integrity`() = runBlocking {
        val (server, _) = server(slow = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val output = File(temp.newFolder(), "file")
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path)
            val done = CompletableDeferred<Unit>()
            val task = DownloadTask(item, OkHttpClient(), onStatusChange = { _, status ->
                if (status == DownloadStatus.COMPLETED) done.complete(Unit)
                if (status == DownloadStatus.FAILED) done.completeExceptionally(IllegalStateException(item.errorMessage))
            })
            task.start(scope)
            withTimeout(5000) { while (item.downloadedBytes == 0L) delay(10) }
            repeat(3) {
                task.pause()
                task.resume(scope)
                delay(30)
            }
            withTimeout(15_000) { done.await() }
            assertArrayEquals(payload, output.readBytes())
        } finally { scope.cancel(); server.stop(0) }
    }

    @Test fun `cancel waits for workers and never resurrects partial output`() = runBlocking {
        val (server, _) = server(slow = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val output = File(temp.newFolder(), "file")
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path)
            val task = DownloadTask(item, OkHttpClient())
            task.start(scope)
            withTimeout(5000) { while (item.downloadedBytes == 0L) delay(10) }
            task.cancel(scope).join()
            delay(100)
            assertEquals(DownloadStatus.CANCELLED, item.status)
            assertFalse(File(output.path + ".part").exists())
            assertFalse(output.exists())
        } finally { scope.cancel(); server.stop(0) }
    }

    @Test fun `existing destination is not overwritten and finalization can retry`() = runBlocking {
        val (server, _) = server()
        try {
            val output = File(temp.newFolder(), "file")
            output.writeText("unrelated")
            val item = DownloadItem(1, "http://127.0.0.1:${server.address.port}/file", "file", output.path)
            assertEquals(DownloadStatus.FAILED, runTask(item))
            assertEquals("unrelated", output.readText())
            assertTrue(File(output.path + ".part").exists())
        } finally { server.stop(0) }
    }

    @Test fun `no validator never permits unsafe append`() = runBlocking {
        val output = File(temp.newFolder(), "file")
        val partial = File(output.path + ".part")
        partial.writeBytes(byteArrayOf(1, 2, 3))
        val item = DownloadItem(1, "http://127.0.0.1:1/file", "file", output.path, totalBytes = 100, downloadedBytes = 3)
        assertEquals(DownloadStatus.FAILED, runTask(item, listOf(PersistedSegment(0, 99, 3, false))))
        assertArrayEquals(byteArrayOf(1, 2, 3), partial.readBytes())
    }
}
