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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presentation preferences that shape how the Download Manager renders its
 * list. Exposed as one immutable snapshot so the route observes a single
 * flow and the list recomposes once per preference change, not once per key.
 */
data class DownloadManagerPresentation(
    val layout: DownloadManagerLayout = DownloadManagerLayout.MediaGallery,
    val enabledFilters: Set<DownloadFilterCategory> =
        DownloadFilterCategory.fromStoredIds(DownloadSettingsDefaults.DEFAULT_FILTER_IDS),
    val visualVideoPresentation: Boolean = true,
    val showFilterCounts: Boolean = true,
)

@HiltViewModel
class DownloadLayoutViewModel @Inject constructor(
    private val preferences: AppPreferences,
) : ViewModel() {

    val presentation: StateFlow<DownloadManagerPresentation?> = preferences.settings
        .map { settings ->
            DownloadManagerPresentation(
                layout = DownloadManagerLayout.fromStoredValue(settings.downloadManagerLayout),
                enabledFilters = DownloadFilterCategory.fromStoredIds(settings.downloadFilterIds),
                visualVideoPresentation = settings.visualVideoPresentation,
                showFilterCounts = settings.showDownloadFilterCounts,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun setLayout(layout: DownloadManagerLayout) {
        viewModelScope.launch { preferences.setDownloadManagerLayout(layout.storedValue) }
    }
}
