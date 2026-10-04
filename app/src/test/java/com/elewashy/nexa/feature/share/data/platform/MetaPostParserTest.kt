package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures follow the logged-out Instagram/Threads page schema (`XIGPolaris*Media`). */
class MetaPostParserTest {

    private fun page(vararg jsonBlocks: String) = buildString {
        append("<html><head><script>var notJson = {\"code\":\"ABC\"};</script>")
        jsonBlocks.forEach { append("<script type=\"application/json\"  data-content-len=\"1\" data-sjs>").append(it).append("</script>") }
        append("</head><body></body></html>")
    }

    private fun scan(html: String, code: String) = MetaPostParser.scan(Buffer().writeUtf8(html), code)

    /** Real candidates are largest first and may omit their size (then the item's original size applies). */
    private fun photo(code: String, url: String, sized: Boolean = true) =
        """{"__typename":"XIGPolarisImageMedia","code":"$code","media_type":1,"original_width":1080,"original_height":1350,""" +
            """"image_versions2":{"candidates":[""" +
            (if (sized) """{"url":"$url","width":1080,"height":1350},{"url":"$url-small","width":320,"height":400}"""
            else """{"url":"$url"},{"url":"$url-small"}""") + "]}}"

    private val video = """{"__typename":"XIGPolarisVideoMedia","code":"VID","media_type":2,""" +
        """"image_versions2":{"candidates":[{"url":"https://cdn/cover.jpg","width":1080,"height":1920}]},""" +
        """"video_versions":[{"type":101,"url":"https:\/\/cdn\/v.mp4"},{"type":102,"url":"https://cdn/v.mp4"},{"type":103,"url":"https://cdn/v.mp4"}]}"""

    @Test
    fun `photo post yields its largest image`() {
        val media = scan(page("""{"require":[["x",{"items":[${photo("ABC", "https://cdn/photo.jpg")}]}]]}"""), "ABC")!!

        assertEquals(listOf(MediaImage("https://cdn/photo.jpg", 1080, 1350)), media.images)
        assertTrue(media.videos.isEmpty())
    }

    @Test
    fun `unsized candidates take the item's original size`() {
        val media = scan(page(photo("ABC", "https://cdn/photo.jpg", sized = false)), "ABC")!!

        assertEquals(listOf(MediaImage("https://cdn/photo.jpg", 1080, 1350)), media.images)
    }

    @Test
    fun `carousel yields every photo and video slide in order, never covers`() {
        val video2 = video.replace("https:\\/\\/cdn\\/v.mp4", "https://cdn/v2.mp4").replace("https://cdn/v.mp4", "https://cdn/v2.mp4")
        val carousel = """{"__typename":"XIGPolarisCarouselMedia","code":"CAR","media_type":8,"carousel_media":[""" +
            photo("S1", "https://cdn/1.jpg", sized = false) + "," + video + "," + photo("S2", "https://cdn/2.jpg") + "," + video2 + "]}"
        val media = scan(page("""{"data":{"xig_polaris_media":$carousel}}"""), "CAR")!!

        assertEquals(listOf("https://cdn/1.jpg", "https://cdn/2.jpg"), media.images.map { it.url })
        assertEquals(listOf(listOf("https://cdn/v.mp4"), listOf("https://cdn/v2.mp4")), media.videos.map { v -> v.map { it.url } })
        assertTrue(media.isComplete)
    }

    @Test
    fun `video post yields one deduplicated version and no image`() {
        val media = scan(page("[$video]"), "VID")!!

        assertTrue(media.images.isEmpty())
        assertEquals(listOf(listOf("https://cdn/v.mp4")), media.videos.map { v -> v.map { it.url } })
    }

    @Test
    fun `video listed without playable versions marks the media incomplete`() {
        val hidden = """{"code":"HID","media_type":2,"video_versions":[],"image_versions2":{"candidates":[{"url":"https://cdn/c.jpg"}]}}"""
        val media = scan(page(hidden), "HID")!!

        assertTrue(media.videos.isEmpty())
        assertTrue("a video's cover is never offered as a photo", media.images.isEmpty())
        assertEquals(false, media.isComplete)
    }

    @Test
    fun `text post is found but empty`() {
        val text = """{"code":"TXT","media_type":19,"image_versions2":{"candidates":[]}}"""

        assertTrue(scan(page(text), "TXT")!!.isEmpty)
    }

    @Test
    fun `blocks that only reference the code are skipped until the media item`() {
        // Real pages mention the shortcode in routing/timeline blocks before the media block.
        val html = page(
            """{"route":{"params":{"code":"MINE"}}}""",
            """{"timeline":[{"code":"MINE","display_uri":"https://cdn/thumb.jpg"}]}""",
            """{"items":[${photo("OTHER", "https://cdn/other.jpg")}]}""",
            """{"items":[${photo("MINE", "https://cdn/mine.jpg")}]}""",
        )

        assertEquals(listOf("https://cdn/mine.jpg"), scan(html, "MINE")!!.images.map { it.url })
        assertNull(scan(html, "MISSING"))
    }

    @Test
    fun `malformed data and logged-out shells yield null`() {
        assertNull(scan(page("""{"code":"ABC","image_versions2":"""), "ABC"))
        assertNull(scan("<html><head><title>Instagram</title></head></html>", "ABC"))
        assertNull(scan(page(photo("ABC", "https://cdn/p.jpg")), ""))
    }

    @Test
    fun `deeply nested data does not overflow the stack`() {
        val depth = 1_000
        val nested = "[".repeat(depth) + photo("DEEP", "https://cdn/deep.jpg") + "]".repeat(depth)

        assertEquals(1, scan(page(nested), "DEEP")?.images?.size)
    }
}
