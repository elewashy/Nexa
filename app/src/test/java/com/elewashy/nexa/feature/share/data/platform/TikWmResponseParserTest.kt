package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.model.MediaImage
import com.elewashy.nexa.feature.share.domain.model.MediaLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikWmResponseParserTest {

    @Test
    fun `video posts keep their video and audio options`() {
        val result = TikWmResponseParser.toResult(
            JSONObject("""{"play":"https://v/nowm.mp4","wmplay":"https://v/wm.mp4","music":"https://a/m.mp3"}""")
        )

        assertEquals(
            mapOf(
                "No Watermark" to "https://v/nowm.mp4",
                MediaLabel.watermarked("Watermarked") to "https://v/wm.mp4",
                MediaLabel.audio("Audio") to "https://a/m.mp3",
            ),
            result.videos,
        )
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `photo posts offer every slide and never their soundtrack as a video`() {
        val result = TikWmResponseParser.toResult(
            JSONObject(
                """{"play":"https://a/m.mp3","wmplay":"https://a/m.mp3","music":"https://a/m.mp3",
                   "images":["https://i/1.jpeg","https://i/2.jpeg","https://i/1.jpeg",""]}"""
            )
        )

        assertEquals(listOf(MediaImage("https://i/1.jpeg"), MediaImage("https://i/2.jpeg")), result.images)
        assertEquals(mapOf(MediaLabel.audio("Audio") to "https://a/m.mp3"), result.videos)
        assertTrue(result.success)
    }

    @Test
    fun `relative paths resolve against tikwm`() {
        val result = TikWmResponseParser.toResult(JSONObject("""{"images":["/img/1.jpg"]}"""))

        assertEquals("https://www.tikwm.com/img/1.jpg", result.images.single().url)
    }

    @Test(expected = ExtractionException::class)
    fun `payload without media is rejected`() {
        TikWmResponseParser.toResult(JSONObject("""{"title":"nothing"}"""))
    }
}
