package com.elewashy.nexa.feature.downloads.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.downloads.domain.model.DownloadFilterCategory
import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout
import com.elewashy.nexa.feature.downloads.domain.model.DownloadSettingsDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class DownloadSettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
) : ViewModel() {
    private val filterMutationMutex = Mutex()

    // AppPreferences already clamps and sanitizes each value on read; this only
    // projects the stored primitives onto the domain types. Kotlin's typed
    // combine caps at five flows, hence the two-stage composition.
    val state: StateFlow<DownloadSettingsUiState> = combine(
        combine(
            preferences.downloadManagerLayout,
            preferences.maxConcurrentDownloads,
            preferences.downloadFilterIds,
            preferences.downloadSpeedLimitBytesPerSecond,
            preferences.autoRetryDownloads,
        ) { layout, concurrent, filterIds, speed, autoRetry ->
            DownloadSettingsUiState(
                layout = DownloadManagerLayout.fromStoredValue(layout),
                maxConcurrentDownloads = concurrent,
                enabledFilters = DownloadFilterCategory.fromStoredIds(filterIds),
                speedLimitBytesPerSecond = speed,
                autoRetry = autoRetry,
            )
        },
        preferences.visualVideoPresentation,
        preferences.showDownloadFilterCounts,
    ) { transfers, videoPreviews, showFilterCounts ->
        transfers.copy(
            visualVideoPresentation = videoPreviews,
            showFilterCounts = showFilterCounts,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadSettingsUiState(),
    )

    fun setMaxConcurrentDownloads(value: Int) {
        viewModelScope.launch { preferences.setMaxConcurrentDownloads(value) }
    }

    fun toggleFilter(category: DownloadFilterCategory) {
        viewModelScope.launch {
            filterMutationMutex.withLock {
                val next = DownloadFilterCategory.fromStoredIds(preferences.downloadFilterIds.first())
                    .toMutableSet()
                    .apply { if (!add(category)) remove(category) }
                preferences.setDownloadFilterIds(next.mapTo(linkedSetOf()) { it.storedId })
            }
        }
    }

    fun setSpeedLimit(value: Long) {
        viewModelScope.launch { preferences.setDownloadSpeedLimitBytesPerSecond(value) }
    }

    fun setAutoRetry(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoRetryDownloads(enabled) }
    }

    fun setVisualVideoPresentation(enabled: Boolean) {
        viewModelScope.launch { preferences.setVisualVideoPresentation(enabled) }
    }

    fun setShowFilterCounts(show: Boolean) {
        viewModelScope.launch { preferences.setShowDownloadFilterCounts(show) }
    }
}

data class DownloadSettingsUiState(
    val layout: DownloadManagerLayout = DownloadManagerLayout.MediaGallery,
    val maxConcurrentDownloads: Int = DownloadSettingsDefaults.DEFAULT_CONCURRENT_DOWNLOADS,
    val enabledFilters: Set<DownloadFilterCategory> = DownloadFilterCategory.fromStoredIds(
        DownloadSettingsDefaults.DEFAULT_FILTER_IDS
    ),
    val speedLimitBytesPerSecond: Long = DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND,
    val autoRetry: Boolean = true,
    val visualVideoPresentation: Boolean = true,
    val showFilterCounts: Boolean = true,
)
