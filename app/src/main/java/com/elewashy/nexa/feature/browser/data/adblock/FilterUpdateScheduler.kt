package com.elewashy.nexa.feature.browser.data.adblock

import android.util.Log
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.network.NetworkMonitor
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.browser.domain.model.FilterUpdateInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs automatic filter-list updates while the app process is alive,
 * honouring the interval chosen in Settings.
 *
 * The loop sleeps until the earliest list becomes due instead of polling,
 * restarts immediately when the interval setting changes, and pauses while
 * the device is offline (so no failed attempts pile up). In
 * [FilterUpdateInterval.Manual] mode only lists that were never downloaded
 * are fetched; everything else waits for an explicit update from Settings.
 */
@Singleton
class FilterUpdateScheduler @Inject constructor(
    private val adBlockRepository: AdBlockRepository,
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
            combine(appPreferences.filterUpdateIntervalHours, networkMonitor.online) { hours, online ->
                FilterUpdateInterval.fromStoredValue(hours) to online
            }
                .distinctUntilChanged()
                .collectLatest { (interval, online) ->
                    if (online) runUpdates(interval.intervalMs)
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
    private fun nextWaitMs(intervalMs: Long?): Long? {
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
