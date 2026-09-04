package com.elewashy.nexa.feature.downloads.data.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/** Aggregate, process-local byte-rate limiter shared by every segment and download. */
class BandwidthLimiter(initialBytesPerSecond: Long = 0L) {
    private val mutex = Mutex()
    @Volatile private var bytesPerSecond = initialBytesPerSecond.coerceAtLeast(0L)
    private var nextAvailableNanos = 0L

    fun updateLimit(value: Long) {
        bytesPerSecond = value.coerceAtLeast(0L)
    }

    suspend fun throttle(bytes: Int) {
        if (bytes <= 0 || bytesPerSecond <= 0L) return
        mutex.withLock {
            var observedLimit = bytesPerSecond
            while (true) {
                val currentLimit = bytesPerSecond
                if (currentLimit <= 0L) {
                    nextAvailableNanos = 0L
                    return
                }
                if (currentLimit != observedLimit) {
                    // A new setting must not inherit a potentially long wait from the old rate.
                    observedLimit = currentLimit
                    nextAvailableNanos = System.nanoTime()
                }

                val now = System.nanoTime()
                val waitNanos = (nextAvailableNanos - now).coerceAtLeast(0L)
                if (waitNanos > 0L) {
                    delay(
                        ((waitNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                            .coerceAtMost(LIMIT_RECHECK_MILLISECONDS)
                    )
                    continue
                }

                val duration = (bytes.toDouble() * NANOS_PER_SECOND / currentLimit)
                    .toLong()
                    .coerceAtLeast(1L)
                nextAvailableNanos = max(now, nextAvailableNanos) + duration
                return
            }
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val LIMIT_RECHECK_MILLISECONDS = 100L
    }
}
