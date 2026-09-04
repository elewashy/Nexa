package com.elewashy.nexa.feature.downloads.data.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class BandwidthLimiterTest {
    @Test
    fun changingToUnlimitedReleasesAnExistingLowRateWait() = runTest {
        val limiter = BandwidthLimiter(initialBytesPerSecond = 1_024L)
        limiter.throttle(bytes = 1_024) // Reserves the next one-second slot.

        val waiting = async { limiter.throttle(bytes = 1_024) }
        delay(1)
        limiter.updateLimit(0L)

        withTimeout(250) { waiting.await() }
    }
}
