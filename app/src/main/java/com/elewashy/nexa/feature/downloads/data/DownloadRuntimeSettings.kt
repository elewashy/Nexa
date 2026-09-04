package com.elewashy.nexa.feature.downloads.data

import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.downloads.domain.model.DownloadSettingsDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Narrow preferences boundary consumed by the long-lived download engine. */
@Singleton
class DownloadRuntimeSettings private constructor(
    val maxConcurrentDownloads: Flow<Int>,
    val speedLimitBytesPerSecond: Flow<Long>,
    val autoRetry: Flow<Boolean>,
) {
    @Inject constructor(
        preferences: AppPreferences,
    ) : this(
        maxConcurrentDownloads = preferences.maxConcurrentDownloads,
        speedLimitBytesPerSecond = preferences.downloadSpeedLimitBytesPerSecond,
        autoRetry = preferences.autoRetryDownloads,
    )

    /** Deterministic defaults for local repository tests that do not construct app DataStore. */
    internal constructor() : this(
        maxConcurrentDownloads = flowOf(DownloadSettingsDefaults.DEFAULT_CONCURRENT_DOWNLOADS),
        speedLimitBytesPerSecond = flowOf(DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND),
        autoRetry = flowOf(true),
    )
}
