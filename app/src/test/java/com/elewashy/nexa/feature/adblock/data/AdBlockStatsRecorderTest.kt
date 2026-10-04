package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.feature.adblock.data.engine.RequestType
import com.elewashy.nexa.feature.adblock.data.persistence.AdBlockStatsDao
import com.elewashy.nexa.feature.adblock.data.persistence.BlockedDomainEntity
import com.elewashy.nexa.feature.adblock.data.persistence.DailyStatsEntity
import com.elewashy.nexa.feature.adblock.data.persistence.StatsBatch
import com.elewashy.nexa.feature.adblock.data.persistence.StatsTotalsRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AdBlockStatsRecorderTest {

    /** Records batches; the queries are not needed by the recorder. */
    private class FakeDao : AdBlockStatsDao {
        val batches = mutableListOf<StatsBatch>()
        override suspend fun apply(batch: StatsBatch) {
            batches += batch
        }
        override fun observeTotals(): Flow<StatsTotalsRow> = emptyFlow()
        override fun observeDaysFrom(fromDay: Long): Flow<List<DailyStatsEntity>> = emptyFlow()
        override fun observeTopDomains(limit: Int): Flow<List<BlockedDomainEntity>> = emptyFlow()
        override suspend fun ensureDay(day: Long) = Unit
        override suspend fun addToDay(
            day: Long, blockedRequests: Long, blockedPopups: Long, blockedPages: Long,
            removedParams: Long, bytesSaved: Long, pagesFiltered: Long,
        ) = Unit
        override suspend fun ensureDomain(domain: String, at: Long) = Unit
        override suspend fun addToDomain(domain: String, count: Long, at: Long) = Unit
        override suspend fun domainCount(): Int = 0
        override suspend fun pruneDomains(keep: Int) = Unit
        override suspend fun clearDays() = Unit
        override suspend fun clearDomains() = Unit
    }

    private val day = LocalDate.of(2026, 10, 4)
    private val noon = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun TestScope.recorder(dao: FakeDao, now: () -> Long = { noon }): AdBlockStatsRecorder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AdBlockStatsRecorder(dao, this, dispatcher, now) { ZoneOffset.UTC }
    }

    @Test
    fun `counters are written in one batch after the flush delay, not per request`() = runTest {
        val dao = FakeDao()
        val recorder = recorder(dao)
        repeat(3) { recorder.recordBlockedRequest("ads.tracker.example", RequestType.SCRIPT, attributeHost = true) }
        recorder.recordBlockedPopup()
        runCurrent()
        assertTrue(dao.batches.isEmpty())

        advanceTimeBy(AdBlockStatsRecorder.FLUSH_DELAY_MS + 1)
        runCurrent()

        assertEquals(1, dao.batches.size)
        val batch = dao.batches.single()
        val stats = batch.days.single()
        assertEquals(day.toEpochDay(), stats.day)
        assertEquals(3L, stats.blockedRequests)
        assertEquals(1L, stats.blockedPopups)
        assertEquals(3 * AdBlockStatsRecorder.estimatedBytes(RequestType.SCRIPT), stats.bytesSaved)
        assertEquals(mapOf("tracker.example" to 3L), batch.domains)
    }

    @Test
    fun `private tabs add to the totals but never record hosts`() = runTest {
        val dao = FakeDao()
        val recorder = recorder(dao)
        recorder.recordBlockedRequest("ads.example", RequestType.IMAGE, attributeHost = false)
        recorder.flush()
        assertEquals(1L, dao.batches.single().days.single().blockedRequests)
        assertTrue(dao.batches.single().domains.isEmpty())
    }

    @Test
    fun `nothing is written when nothing happened`() = runTest {
        val dao = FakeDao()
        recorder(dao).flush()
        assertTrue(dao.batches.isEmpty())
    }

    @Test
    fun `counts recorded after midnight go to the new day`() = runTest {
        val dao = FakeDao()
        var now = noon
        val recorder = recorder(dao) { now }
        recorder.recordBlockedPage()
        now += 24 * 60 * 60 * 1000L
        recorder.recordBlockedPage()
        recorder.recordBlockedPage()
        recorder.flush()
        val byDay = dao.batches.flatMap { it.days }.associate { it.day to it.blockedPages }
        assertEquals(mapOf(day.toEpochDay() to 1L, day.plusDays(1).toEpochDay() to 2L), byDay)
    }
}
