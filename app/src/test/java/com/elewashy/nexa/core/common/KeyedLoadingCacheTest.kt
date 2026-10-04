package com.elewashy.nexa.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedLoadingCacheTest {

    private var now = 0L
    private fun cache(maxEntries: Int = 8, ttlMs: Long = 1_000) =
        KeyedLoadingCache<String, String?>(maxEntries, ttlMs, nowMs = { now })

    @Test
    fun `concurrent requests for one key load once`() = runTest {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val first = async { cache.get("a") { loads++; gate.await(); "A" } }
        val second = async { cache.get("a") { loads++; "other" } }
        runCurrent()
        gate.complete(Unit)

        assertEquals("A", first.await())
        assertEquals("A", second.await())
        assertEquals(1, loads)
    }

    @Test
    fun `a slow load does not block other keys`() = runTest {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        val slow = async { cache.get("slow") { gate.await(); "S" } }
        runCurrent()

        assertEquals("F", cache.get("fast") { "F" })
        assertFalse(slow.isCompleted)
        gate.complete(Unit)
        assertEquals("S", slow.await())
    }

    @Test
    fun `null results are cached and failures are not`() = runTest {
        val cache = cache()
        var loads = 0

        assertNull(cache.get("gone") { loads++; null })
        assertNull(cache.get("gone") { loads++; "reloaded" })
        assertEquals(1, loads)

        val failure = runCatching { cache.get("flaky") { throw IOException("offline") } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("ok", cache.get("flaky") { "ok" })
    }

    @Test
    fun `entries expire after the ttl`() = runTest {
        val cache = cache(ttlMs = 1_000)
        cache.get("a") { "old" }
        now = 999
        assertEquals("old", cache.get("a") { "new" })
        now = 1_000
        assertEquals("new", cache.get("a") { "new" })
    }

    @Test
    fun `least recently used entries are evicted`() = runTest {
        val cache = cache(maxEntries = 2)
        cache.get("a") { "A" }
        cache.get("b") { "B" }
        cache.get("a") { "unused" } // touch a, so b is the eldest
        cache.get("c") { "C" }

        assertEquals("A", cache.get("a") { "reloaded" })
        assertEquals("reloaded", cache.get("b") { "reloaded" })
    }

    @Test
    fun `a cancelled waiter does not cancel the load it waited for`() = runTest {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        val owner = async { cache.get("a") { gate.await(); "A" } }
        runCurrent()
        val waiter = launch { cache.get("a") { "other" } }
        runCurrent()
        waiter.cancel()
        gate.complete(Unit)

        assertEquals("A", owner.await())
        assertEquals("A", cache.get("a") { "other" })
    }
}
