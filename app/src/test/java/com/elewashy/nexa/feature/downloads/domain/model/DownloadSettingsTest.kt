package com.elewashy.nexa.feature.downloads.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSettingsTest {
    @Test
    fun defaultsPreserveThreeConcurrentDownloadsAndExistingFilters() {
        assertEquals(3, DownloadSettingsDefaults.DEFAULT_CONCURRENT_DOWNLOADS)
        assertEquals(5, DownloadSettingsDefaults.MAX_CONCURRENT_DOWNLOADS)
        assertEquals(
            setOf("videos", "audio", "other"),
            DownloadSettingsDefaults.DEFAULT_FILTER_IDS,
        )
    }

    @Test
    fun customSpeedRequiresKilobytesAndIsBounded() {
        assertEquals(1024L, DownloadSettingsDefaults.parseCustomSpeedLimit("1"))
        assertEquals(null, DownloadSettingsDefaults.parseCustomSpeedLimit(""))
        assertEquals(null, DownloadSettingsDefaults.parseCustomSpeedLimit("0"))
        assertEquals(1L shl 30, DownloadSettingsDefaults.parseCustomSpeedLimit("1048576"))
        assertEquals(null, DownloadSettingsDefaults.parseCustomSpeedLimit("1048577"))
        assertEquals(null, DownloadSettingsDefaults.parseCustomSpeedLimit("999999999"))
        assertEquals(512L * 1024, DownloadSettingsDefaults.parseCustomSpeedLimit("٥١٢"))
        assertEquals(null, DownloadSettingsDefaults.parseCustomSpeedLimit("12.5"))
        assertEquals(null, DownloadSettingsDefaults.parseCustomSpeedLimit("-1"))
    }

    @Test
    fun concurrentValueIsClampedToEngineSupportedRange() {
        assertEquals(1, DownloadSettingsDefaults.clampConcurrentDownloads(-1))
        assertEquals(3, DownloadSettingsDefaults.clampConcurrentDownloads(3))
        assertEquals(5, DownloadSettingsDefaults.clampConcurrentDownloads(99))
    }

    @Test
    fun storedFilterIdsIgnoreUnknownFutureOrCorruptValues() {
        val categories = DownloadFilterCategory.fromStoredIds(setOf("pdf", "unknown", "apk"))
        assertEquals(setOf(DownloadFilterCategory.Pdf, DownloadFilterCategory.Apk), categories)
    }

    @Test
    fun classifierUsesMimeAndExtensionWithSpecificTypesBeforeOther() {
        assertEquals(DownloadFilterCategory.Apk, item("package.bin", "application/vnd.android.package-archive").category())
        assertEquals(DownloadFilterCategory.Pdf, item("guide.PDF", null).category())
        assertEquals(DownloadFilterCategory.Archives, item("backup.tar", "application/octet-stream").category())
        assertEquals(DownloadFilterCategory.Images, item("photo.webp", null).category())
        assertEquals(DownloadFilterCategory.Other, item("notes.txt", "text/plain").category())
    }

    @Test
    fun otherIsTheComplementOfEnabledDedicatedCategories() {
        val pdf = item("guide.pdf", "application/pdf")
        val video = item("movie.mp4", "video/mp4")
        val videosAndOther = setOf(DownloadFilterCategory.Videos, DownloadFilterCategory.Other)
        val pdfAndOther = setOf(DownloadFilterCategory.Pdf, DownloadFilterCategory.Other)

        assertTrue(DownloadFilterPolicy.matches(pdf, DownloadFilterCategory.Other, videosAndOther))
        assertTrue(!DownloadFilterPolicy.matches(video, DownloadFilterCategory.Other, videosAndOther))
        assertTrue(!DownloadFilterPolicy.matches(pdf, DownloadFilterCategory.Other, pdfAndOther))
    }

    @Test
    fun visibleFiltersRequireActualContentAndOtherAbsorbsDisabledCategories() {
        val downloads = listOf(
            item("movie.mp4", "video/mp4"),
            item("guide.pdf", "application/pdf"),
        )
        val counts = DownloadFilterPolicy.visibleCounts(
            downloads,
            setOf(
                DownloadFilterCategory.Videos,
                DownloadFilterCategory.Audio,
                DownloadFilterCategory.Other,
            ),
        )

        assertEquals(1, counts[DownloadFilterCategory.Videos])
        assertEquals(null, counts[DownloadFilterCategory.Audio])
        assertEquals(1, counts[DownloadFilterCategory.Other])
    }

    @Test
    fun enablingDedicatedCategoryMovesItsItemsOutOfOtherWithoutSpecialCases() {
        val downloads = listOf(
            item("guide.pdf", "application/pdf"),
            item("package.apk", "application/vnd.android.package-archive"),
        )
        val before = DownloadFilterPolicy.visibleCounts(
            downloads,
            setOf(DownloadFilterCategory.Other),
        )
        val after = DownloadFilterPolicy.visibleCounts(
            downloads,
            setOf(DownloadFilterCategory.Pdf, DownloadFilterCategory.Other),
        )

        assertEquals(2, before[DownloadFilterCategory.Other])
        assertEquals(1, after[DownloadFilterCategory.Pdf])
        assertEquals(1, after[DownloadFilterCategory.Other])
    }

    @Test
    fun everyOptionalCategoryHasAStableUniqueId() {
        assertEquals(DownloadFilterCategory.entries.size, DownloadFilterCategory.entries.map { it.storedId }.toSet().size)
        assertTrue(DownloadFilterCategory.entries.all { it.storedId.isNotBlank() })
    }

    private fun item(name: String, mime: String?) = DownloadItem(
        id = 1,
        url = "https://example.com/$name",
        fileName = name,
        filePath = "/tmp/$name",
        mimeType = mime,
    )

    private fun DownloadItem.category() = DownloadFileClassifier.category(this)
}
