package com.elewashy.nexa.feature.adblock.data

import android.util.Log
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.network.NetworkMonitor
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs automatic filter-list updates while the app process is alive,
 * honouring the interval chosen on the Ad blocker page.
 *
 * The loop sleeps until the earliest list becomes due instead of polling,
 * restarts immediately when the interval setting, the global switch or the
 * list selection changes (a newly enabled list downloads at once), and pauses while
 * the device is offline (so no failed attempts pile up). In
 * [FilterUpdateInterval.Manual] mode only lists that were never downloaded
 * are fetched; everything else waits for an explicit update from the Ad
 * blocker page.
 */
@Singleton
class FilterUpdateScheduler @Inject constructor(
    private val adBlockRepository: AdBlockRepository,
    private val filterListRepository: FilterListRepository,
    private val appPreferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Starts the update loop once per process; later calls are no-ops. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            combine(
                appPreferences.filterUpdateIntervalHours,
                networkMonitor.online,
                appPreferences.adBlockEnabled,
                // A newly selected list is downloaded right away, not at the next interval.
                filterListRepository.enabledLists.map { lists -> lists.mapTo(HashSet()) { it.key } },
            ) { hours, online, enabled, _ ->
                Triple(FilterUpdateInterval.fromStoredValue(hours), online, enabled)
            }
                .collectLatest { (interval, online, enabled) ->
                    if (online && enabled) runUpdates(interval.intervalMs)
                }
        }
    }

    private suspend fun runUpdates(intervalMs: Long?) {
        while (true) {
            try {
                adBlockRepository.refresh(intervalMs, force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Automatic filter update failed", e)
            }
            delay(nextWaitMs(intervalMs) ?: return)
        }
    }

    /** Time until the next check, or null when nothing is scheduled (manual mode, all lists present). */
    private suspend fun nextWaitMs(intervalMs: Long?): Long? {
        val now = System.currentTimeMillis()
        val nextDue = adBlockRepository.nextDueAt(intervalMs)
            ?: return if (adBlockRepository.hasMissingLists()) RETRY_DELAY_MS else null
        // Overdue after a refresh means the last attempt failed: retry after the backoff.
        if (nextDue <= now) return RETRY_DELAY_MS
        return (nextDue - now).coerceAtMost(intervalMs ?: Long.MAX_VALUE)
    }

    private companion object {
        const val TAG = "FilterUpdateScheduler"

        /** Matches the repository's failure backoff, so a retry is never a no-op. */
        const val RETRY_DELAY_MS = 30 * 60 * 1000L + 1_000L
    }
}
