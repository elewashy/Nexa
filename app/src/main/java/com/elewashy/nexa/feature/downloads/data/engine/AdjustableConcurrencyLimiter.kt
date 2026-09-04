package com.elewashy.nexa.feature.downloads.data.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fair, dynamically adjustable concurrency gate. Lower limits apply as current work completes. */
internal class AdjustableConcurrencyLimiter(initialLimit: Int) {
    private val mutex = Mutex()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var limit = initialLimit.coerceAtLeast(1)
    private var active = 0

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    suspend fun updateLimit(value: Int) {
        mutex.withLock {
            limit = value.coerceAtLeast(1)
            drainWaitersLocked()
        }
    }

    private suspend fun acquire() {
        val waiter = mutex.withLock {
            if (active < limit && waiters.isEmpty()) {
                active++
                return
            }
            CompletableDeferred<Unit>().also(waiters::addLast)
        }
        try {
            // A woken waiter already owns an active slot (see drainWaitersLocked).
            waiter.await()
        } catch (error: Throwable) {
            val granted = mutex.withLock {
                if (waiters.remove(waiter)) false else waiter.isCompleted
            }
            if (granted) withContext(NonCancellable) { release() }
            throw error
        }
    }

    private suspend fun release() {
        mutex.withLock {
            check(active > 0) { "Concurrency permit released without an owner" }
            active--
            drainWaitersLocked()
        }
    }

    private fun drainWaitersLocked() {
        while (active < limit && waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            // Completion can lose a race with cancellation; only a successful
            // completion transfers ownership of a slot.
            if (waiter.complete(Unit)) active++
        }
    }
}
