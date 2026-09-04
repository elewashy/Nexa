package com.elewashy.nexa.feature.downloads.presentation.settings

import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout

/** Logical groups used by the single adaptive Download Manager settings screen. */
internal enum class DownloadSettingsSection {
    Appearance,
    Transfers,
    Filters,
}

internal enum class DownloadSettingKey {
    Design,
    VideoPreviewCards,
    ConcurrentDownloads,
    SpeedLimit,
    AutomaticRetry,
    FilterCounts,
    FilterCategories,
}

internal data class DownloadSettingDefinition(
    val key: DownloadSettingKey,
    val section: DownloadSettingsSection,
    /** Empty means shared by every design, including designs added in the future. */
    val applicableLayouts: Set<DownloadManagerLayout> = emptySet(),
) {
    fun appliesTo(layout: DownloadManagerLayout): Boolean =
        applicableLayouts.isEmpty() || layout in applicableLayouts
}

internal data class VisibleDownloadSettingsSection(
    val section: DownloadSettingsSection,
    val settings: List<DownloadSettingKey>,
)

/** Central source of truth for shared versus design-specific settings. */
internal object DownloadSettingsCatalog {
    private val definitions = listOf(
        DownloadSettingDefinition(DownloadSettingKey.Design, DownloadSettingsSection.Appearance),
        DownloadSettingDefinition(DownloadSettingKey.VideoPreviewCards, DownloadSettingsSection.Appearance),
        DownloadSettingDefinition(DownloadSettingKey.ConcurrentDownloads, DownloadSettingsSection.Transfers),
        DownloadSettingDefinition(DownloadSettingKey.SpeedLimit, DownloadSettingsSection.Transfers),
        DownloadSettingDefinition(DownloadSettingKey.AutomaticRetry, DownloadSettingsSection.Transfers),
        DownloadSettingDefinition(
            DownloadSettingKey.FilterCounts,
            DownloadSettingsSection.Filters,
            applicableLayouts = setOf(DownloadManagerLayout.MediaGallery),
        ),
        DownloadSettingDefinition(
            DownloadSettingKey.FilterCategories,
            DownloadSettingsSection.Filters,
            applicableLayouts = setOf(DownloadManagerLayout.MediaGallery),
        ),
    )

    fun sectionsFor(layout: DownloadManagerLayout): List<VisibleDownloadSettingsSection> =
        DownloadSettingsSection.entries.mapNotNull { section ->
            definitions
                .filter { it.section == section && it.appliesTo(layout) }
                .map(DownloadSettingDefinition::key)
                .takeIf { it.isNotEmpty() }
                ?.let { VisibleDownloadSettingsSection(section, it) }
        }
}
