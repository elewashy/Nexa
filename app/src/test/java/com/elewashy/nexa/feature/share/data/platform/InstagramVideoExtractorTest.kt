package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/** JVM tests for how [InstagramVideoExtractor] combines the embed and the post page. */
class InstagramVideoExtractorTest {

    private fun media(videos: Int, images: Int, isComplete: Boolean = true) = MetaPostMedia(
        videos = List(videos) { listOf(VideoVersion("https://cdn/v$it.mp4", 720, 1280)) },
        images = List(images) { MediaImage("https://cdn/i$it.jpg") },
        isComplete = isComplete,
    )

    private val unreachable: suspend () -> MetaPostMedia? = { throw IOException("offline") }

    @Test
    fun `complete embed is used without reading the post page`() = runTest {
        val embed = media(videos = 1, images = 1)

        assertSame(embed, InstagramVideoExtractor.choosePost(embed) { error("post page must not be read") })
    }

    @Test
    fun `incomplete embed is replaced by the post page`() = runTest {
        val page = media(videos = 2, images = 1)

        assertSame(page, InstagramVideoExtractor.choosePost(media(videos = 1, images = 1, isComplete = false)) { page })
    }

    @Test
    fun `incomplete post page with less media does not replace a richer embed`() = runTest {
        val embed = media(videos = 1, images = 3, isComplete = false)

        assertSame(embed, InstagramVideoExtractor.choosePost(embed) { media(videos = 0, images = 1, isComplete = false) })
    }

    @Test
    fun `incomplete embed is kept when the post page has no data or is unreachable`() = runTest {
        val embed = media(videos = 0, images = 2, isComplete = false)

        assertSame(embed, InstagramVideoExtractor.choosePost(embed) { null })
        assertSame(embed, InstagramVideoExtractor.choosePost(embed, unreachable))
    }

    @Test
    fun `without an embed the post page decides and its network failure propagates`() = runTest {
        val page = media(videos = 1, images = 0)

        assertSame(page, InstagramVideoExtractor.choosePost(null) { page })
        assertNull(InstagramVideoExtractor.choosePost(null) { null })
        val failure = runCatching { InstagramVideoExtractor.choosePost(null, unreachable) }.exceptionOrNull()
        assertEquals(IOException::class.java, failure?.javaClass)
    }
}
