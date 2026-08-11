package com.elewashy.nexa.feature.share

import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLabelTest {

    @Test
    fun `audio labels round trip`() {
        val encoded = MediaLabel.audio("Audio")
        assertTrue(encoded.startsWith(MediaLabel.AUDIO_PREFIX))

        val (kind, label) = MediaLabel.parse(encoded)
        assertEquals(MediaLabel.Kind.AUDIO, kind)
        assertEquals("Audio", label)
    }

    @Test
    fun `watermark labels round trip`() {
        val encoded = MediaLabel.watermarked("Watermarked - 12.3 MB")

        val (kind, label) = MediaLabel.parse(encoded)
        assertEquals(MediaLabel.Kind.WATERMARKED_VIDEO, kind)
        assertEquals("Watermarked - 12.3 MB", label)
    }

    @Test
    fun `conversion labels round trip`() {
        val encoded = MediaLabel.conversion("resource-content-payload")

        val (kind, label) = MediaLabel.parse(encoded)
        assertEquals(MediaLabel.Kind.CONVERSION, kind)
        assertEquals("resource-content-payload", label)
    }

    @Test
    fun `plain labels parse as video`() {
        val (kind, label) = MediaLabel.parse("720p - 12.3 MB")
        assertEquals(MediaLabel.Kind.VIDEO, kind)
        assertEquals("720p - 12.3 MB", label)
    }

    @Test
    fun `audio prefix wins over watermark when both could match`() {
        val (kind, _) = MediaLabel.parse("${MediaLabel.AUDIO_PREFIX}text")
        assertEquals(MediaLabel.Kind.AUDIO, kind)
        assertFalse(kind == MediaLabel.Kind.WATERMARKED_VIDEO)
    }
}
