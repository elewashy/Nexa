package com.elewashy.nexa.feature.downloads.data.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustableConcurrencyLimiterTest {
    @Test
    fun increasingLimitStartsQueuedWork() = runTest {
        val limiter = AdjustableConcurrencyLimiter(1)
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            limiter.withPermit {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val second = async { limiter.withPermit { secondStarted.complete(Unit) } }
        testScheduler.runCurrent()
        assertFalse(secondStarted.isCompleted)

        limiter.updateLimit(2)
        secondStarted.await()
        assertTrue(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun reducingLimitWaitsForExistingOwnersWithoutInterruptingThem() = runTest {
        val limiter = AdjustableConcurrencyLimiter(2)
        val releases = List(2) { CompletableDeferred<Unit>() }
        val started = List(3) { CompletableDeferred<Unit>() }
        val jobs = List(2) { index ->
            async { limiter.withPermit { started[index].complete(Unit); releases[index].await() } }
        }
        started[0].await()
        started[1].await()
        limiter.updateLimit(1)
        val third = async { limiter.withPermit { started[2].complete(Unit) } }
        testScheduler.runCurrent()
        assertFalse(started[2].isCompleted)

        releases[0].complete(Unit)
        jobs[0].await()
        testScheduler.runCurrent()
        assertFalse(started[2].isCompleted)

        releases[1].complete(Unit)
        jobs[1].await()
        started[2].await()
        third.await()
    }
}
