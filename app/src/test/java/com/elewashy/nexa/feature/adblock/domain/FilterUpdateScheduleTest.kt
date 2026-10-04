package com.elewashy.nexa.feature.adblock.domain

import com.elewashy.nexa.feature.adblock.data.AdBlockStatsRepository
import com.elewashy.nexa.feature.adblock.data.persistence.DailyStatsEntity
import com.elewashy.nexa.feature.adblock.domain.model.FilterListCategory
import com.elewashy.nexa.feature.adblock.domain.model.FilterListInfo
import com.elewashy.nexa.feature.adblock.domain.model.FilterListState
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateInterval
import com.elewashy.nexa.feature.adblock.domain.usecase.ObserveFilterUpdateScheduleUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FilterUpdateScheduleTest {

    private fun list(key: String, enabled: Boolean, updatedAt: Long, state: FilterListState = FilterListState.Active) =
        FilterListInfo(
            key = key, title = key, category = FilterListCategory.Ads, enabled = enabled, state = state,
            ruleCount = 1, lastUpdatedAt = updatedAt, lastFailedAt = 0L, regions = "", url = null, isDefault = false,
        )

    @Test
    fun `next check is due when the oldest enabled list expires`() {
        val interval = FilterUpdateInterval.Daily
        val schedule = ObserveFilterUpdateScheduleUseCase.schedule(
            interval,
            listOf(list("a", true, 1_000L), list("b", true, 5_000L), list("off", false, 0L, FilterListState.Disabled)),
        )
        assertEquals(5_000L, schedule.lastUpdatedAt)
        assertEquals(1_000L + interval.intervalMs!!, schedule.nextCheckAt)
    }

    @Test
    fun `a list that was never downloaded is fetched at once`() {
        val schedule = ObserveFilterUpdateScheduleUseCase.schedule(
            FilterUpdateInterval.Weekly,
            listOf(list("a", true, 1_000L), list("new", true, 0L, FilterListState.Downloading)),
        )
        assertEquals(0L, schedule.nextCheckAt)
    }

    @Test
    fun `manual mode schedules nothing`() {
        val schedule = ObserveFilterUpdateScheduleUseCase.schedule(FilterUpdateInterval.Manual, listOf(list("a", true, 1_000L)))
        assertNull(schedule.nextCheckAt)
    }

    @Test
    fun `recent days are zero-filled oldest first`() {
        val last = LocalDate.of(2026, 10, 4)
        val first = last.minusDays(6)
        val rows = listOf(
            DailyStatsEntity(last.toEpochDay(), 5, 0, 0, 0, 100, 1),
            DailyStatsEntity(first.plusDays(2).toEpochDay(), 2, 0, 0, 0, 10, 1),
        )
        val days = AdBlockStatsRepository.recentDays(first, last, rows)
        assertEquals(7, days.size)
        assertEquals(first, days.first().date)
        assertEquals(listOf(0L, 0L, 2L, 0L, 0L, 0L, 5L), days.map { it.blockedRequests })
    }
}
