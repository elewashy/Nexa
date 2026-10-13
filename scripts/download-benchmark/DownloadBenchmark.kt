package com.elewashy.nexa.downloadbenchmark

import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.downloadbenchmark.legacy.DownloadTask as LegacyTask
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DownloadBenchmark {
    @Test fun compare() = runBlocking {
        val size = System.getenv("NEXA_BENCH_MIB")?.toInt() ?: 32
        val payload = ByteArray(size * 1024 * 1024) { (it * 31).toByte() }
        val delay = System.getenv("NEXA_BENCH_DELAY_MS")?.toLong() ?: 4
        val repeats = System.getenv("NEXA_BENCH_REPEATS")?.toInt() ?: 3
        val directory = Files.createTempDirectory("nexa-benchmark").toFile()
        val requests = AtomicInteger()
        val firstByte = AtomicLong()
        val bytesSent = AtomicLong()
        var ranges = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/file") { exchange ->
            requests.incrementAndGet()
            Thread.sleep(20)
            exchange.responseHeaders.add("ETag", "\"fixture-v1\"")
            exchange.responseHeaders.add("Accept-Ranges", if (ranges) "bytes" else "none")
            val range = exchange.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toInt() ?: payload.lastIndex
            val length = end - start + 1
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.add("Content-Length", payload.size.toString())
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(if (range != null) 206 else 200, length.toLong())
                try {
                    exchange.responseBody.use { stream ->
                        var position = start
                        while (position <= end) {
                            val count = minOf(64 * 1024, end - position + 1)
                            firstByte.compareAndSet(0, System.nanoTime())
                            stream.write(payload, position, count)
                            bytesSent.addAndGet(count.toLong())
                            stream.flush()
                            position += count
                            if (delay > 0) Thread.sleep(delay)
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/file"
        val client = OkHttpClient()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val rows = mutableListOf("implementation,round,mib,elapsed_ms,mib_per_second,server_first_body_ms,requests,wire_bytes,process_cpu_ms,heap_delta_bytes")
        try {
            for (round in 0..repeats) {
                for (implementation in listOf("legacy", "new-parallel", "new-single", "curl")) {
                    ranges = implementation != "new-single"
                    requests.set(0); firstByte.set(0); bytesSent.set(0)
                    val output = File(directory, "$implementation-$round.bin")
                    val item = DownloadItem(round.toLong(), url, output.name, output.path)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val complete = CompletableDeferred<DownloadStatus>()
                    val runtime = Runtime.getRuntime()
                    val beforeHeap = runtime.totalMemory() - runtime.freeMemory()
                    val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                    val beforeCpu = bean.processCpuTime
                    val start = System.nanoTime()
                    try {
                        if (implementation == "curl") {
                            check(ProcessBuilder("curl", "--fail", "--silent", "--output", output.path, url).start().waitFor() == 0)
                        } else {
                            val callback: (DownloadStatus) -> Unit = { status ->
                                if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED) complete.complete(status)
                            }
                            if (implementation == "legacy") LegacyTask(item, client, onStatusChange = { _, status -> callback(status) }).start(scope)
                            else DownloadTask(item, client, onStatusChange = { _, status -> callback(status) }).start(scope)
                            check(withTimeout(120_000) { complete.await() } == DownloadStatus.COMPLETED) { item.errorMessage.orEmpty() }
                        }
                        val elapsed = (System.nanoTime() - start) / 1e6
                        val cpu = (bean.processCpuTime - beforeCpu) / 1e6
                        val heap = runtime.totalMemory() - runtime.freeMemory() - beforeHeap
                        assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(output.readBytes()))
                        if (round > 0) rows.add("$implementation,$round,$size,$elapsed,${size * 1000 / elapsed},${(firstByte.get() - start) / 1e6},${requests.get()},${bytesSent.get()},$cpu,$heap")
                    } finally {
                        scope.cancel()
                        output.delete()
                        Thread.sleep(100)
                    }
                }
            }
            val report = File(System.getenv("NEXA_BENCH_OUTPUT") ?: "/tmp/nexa-download-benchmark.csv")
            report.writeText(rows.joinToString("\n") + "\n")
            println(rows.joinToString("\n"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
            directory.deleteRecursively()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
