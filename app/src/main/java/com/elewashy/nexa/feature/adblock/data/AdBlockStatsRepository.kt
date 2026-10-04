package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.feature.adblock.data.persistence.AdBlockStatsDao
import com.elewashy.nexa.feature.adblock.data.persistence.DailyStatsEntity
import com.elewashy.nexa.feature.adblock.data.persistence.StatsTotalsRow
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatistics
import com.elewashy.nexa.feature.adblock.domain.model.BlockedDomain
import com.elewashy.nexa.feature.adblock.domain.model.DailyBlockingStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Read side of the blocking statistics written by [AdBlockStatsRecorder]. */
@Singleton
class AdBlockStatsRepository @Inject constructor(
    private val dao: AdBlockStatsDao,
    private val recorder: AdBlockStatsRecorder,
) {
    /** Lifetime totals and the last [AdBlockStatistics.RECENT_DAYS] days (zero-filled). */
    val statistics: Flow<AdBlockStatistics> = flow {
        val today = LocalDate.now()
        val firstDay = today.minusDays(AdBlockStatistics.RECENT_DAYS - 1L)
        emitAll(
            combine(dao.observeTotals(), dao.observeDaysFrom(firstDay.toEpochDay())) { totals, days ->
                totals.toModel(recentDays(firstDay, today, days))
            },
        )
    }

    fun topDomains(limit: Int): Flow<List<BlockedDomain>> = dao.observeTopDomains(limit).map { rows ->
        rows.map { BlockedDomain(it.domain, it.blockedCount, it.lastBlockedAt) }
    }

    /** Writes counters still held in memory, so the statistics page is current. */
    suspend fun flushPending() {
        recorder.flush()
    }

    suspend fun reset() {
        // Pending counters belong to the period being reset.
        recorder.flush()
        dao.clear()
    }

    companion object {
        internal fun recentDays(first: LocalDate, last: LocalDate, rows: List<DailyStatsEntity>): List<DailyBlockingStats> {
            val byDay = rows.associateBy { it.day }
            return generateSequence(first) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .map { date ->
                    val row = byDay[date.toEpochDay()]
                    DailyBlockingStats(date, row?.blockedRequests ?: 0L, row?.bytesSaved ?: 0L)
                }
                .toList()
        }

        private fun StatsTotalsRow.toModel(recent: List<DailyBlockingStats>) = AdBlockStatistics(
            blockedRequests = blockedRequests,
            blockedPopups = blockedPopups,
            blockedPages = blockedPages,
            removedParams = removedParams,
            bytesSaved = bytesSaved,
            pagesFiltered = pagesFiltered,
            since = since?.let(LocalDate::ofEpochDay),
            recentDays = recent,
        )
    }
}
