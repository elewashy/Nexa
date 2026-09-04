package com.elewashy.nexa.feature.downloads.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadManagerLayoutTest {

    @Test
    fun `media gallery is the stable default for missing or unknown values`() {
        assertEquals(DownloadManagerLayout.MediaGallery, DownloadManagerLayout.fromStoredValue(0))
        assertEquals(DownloadManagerLayout.MediaGallery, DownloadManagerLayout.fromStoredValue(Int.MAX_VALUE))
    }

    @Test
    fun `tabbed list round trips through its persisted value`() {
        assertEquals(
            DownloadManagerLayout.TabbedList,
            DownloadManagerLayout.fromStoredValue(DownloadManagerLayout.TabbedList.storedValue),
        )
    }
}
