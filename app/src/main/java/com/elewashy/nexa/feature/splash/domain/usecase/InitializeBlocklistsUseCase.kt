package com.elewashy.nexa.feature.splash.domain.usecase

import com.elewashy.nexa.feature.browser.data.adblock.FilterUpdateScheduler
import javax.inject.Inject

/**
 * Starts content-blocking filter maintenance for this process.
 *
 * Fire-and-forget: the splash activity calls [invoke] and finishes
 * immediately. [FilterUpdateScheduler] runs on the application scope, checks
 * the lists that are due for the interval configured in Settings and keeps
 * doing so while the process lives. Cached lists stay active if a refresh
 * fails.
 */
class InitializeBlocklistsUseCase @Inject constructor(
    private val filterUpdateScheduler: FilterUpdateScheduler,
) {
    operator fun invoke() {
        filterUpdateScheduler.start()
    }
}
