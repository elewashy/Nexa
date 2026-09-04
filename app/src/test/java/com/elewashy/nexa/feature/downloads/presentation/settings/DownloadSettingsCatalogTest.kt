package com.elewashy.nexa.feature.downloads.presentation.settings

import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSettingsCatalogTest {
    @Test
    fun sharedSettingsAppearInEveryDesign() {
        DownloadManagerLayout.entries.forEach { layout ->
            val settings = DownloadSettingsCatalog.sectionsFor(layout).flatMap { it.settings }
            assertTrue(DownloadSettingKey.Design in settings)
            assertTrue(DownloadSettingKey.VideoPreviewCards in settings)
            assertTrue(DownloadSettingKey.ConcurrentDownloads in settings)
            assertTrue(DownloadSettingKey.SpeedLimit in settings)
            assertTrue(DownloadSettingKey.AutomaticRetry in settings)
        }
    }

    @Test
    fun filtersAreSpecificToMediaGallery() {
        val gallery = DownloadSettingsCatalog.sectionsFor(DownloadManagerLayout.MediaGallery)
        val tabbed = DownloadSettingsCatalog.sectionsFor(DownloadManagerLayout.TabbedList)

        assertTrue(gallery.any { it.section == DownloadSettingsSection.Filters })
        assertFalse(tabbed.any { it.section == DownloadSettingsSection.Filters })
        assertEquals(
            listOf(DownloadSettingsSection.Appearance, DownloadSettingsSection.Transfers),
            tabbed.map { it.section },
        )
    }
}
