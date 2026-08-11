package com.elewashy.nexa.feature.share

import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityTest {

    private fun quality(
        label: String,
        type: VideoQuality.MediaType = VideoQuality.MediaType.VIDEO,
        size: String? = null
    ) = VideoQuality(quality = label, url = "https://example.com/v.mp4", size = size, type = type)

    @Test
    fun `resolution labels normalise to height`() {
        val labels = quality("576x1024").getDisplayLabels()
        assertEquals("576p", labels.quality)
        assertNull(labels.metadata)
    }

    @Test
    fun `size suffix is extracted into metadata`() {
        val labels = quality("1080p - 12.3 MB").getDisplayLabels()
        assertEquals("1080p", labels.quality)
        assertEquals("12.3 MB", labels.metadata)
    }

    @Test
    fun `explicit size field wins over label size`() {
        val labels = quality("720p", size = "8.0 MB").getDisplayLabels()
        assertEquals("720p", labels.quality)
        assertEquals("8.0 MB", labels.metadata)
    }

    @Test
    fun `label prefixes are stripped from display`() {
        assertEquals("Watermarked", quality("WATERMARK:Watermarked").getDisplayLabels().quality)
        assertEquals("Audio", quality("AUDIO:Audio").getDisplayLabels().quality)
    }

    @Test
    fun `blank labels fall back to type name`() {
        assertEquals("Audio", quality("AUDIO:", type = VideoQuality.MediaType.AUDIO).getDisplayLabels().quality)
        assertEquals("Video", quality("").getDisplayLabels().quality)
    }

    @Test
    fun `quality number labels are normalised`() {
        assertEquals("Quality 1", quality("Quality_1").getDisplayLabels().quality)
    }

    @Test
    fun `non ascii noise is dropped`() {
        val labels = quality("720p \u0627\u0644\u062d\u0635\u0648\u0644").getDisplayLabels()
        assertEquals("720p", labels.quality)
    }

    @Test
    fun `sort priority orders by resolution then audio last`() {
        val options = listOf(
            quality("AUDIO:Audio", type = VideoQuality.MediaType.AUDIO),
            quality("1080p"),
            quality("360p"),
            quality("HD"),
            quality("4K"),
            quality("1920x1080")
        ).sortedByDescending(VideoQuality::getSortPriority)

        assertEquals("4K", options.first().quality)
        assertEquals("AUDIO:Audio", options.last().quality)
        assertTrue(options.indexOf(quality("1080p")) < options.indexOf(quality("360p")))
    }
}
