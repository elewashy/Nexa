package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures follow the embed page's `contextJSON` (legacy GraphQL `shortcode_media`). */
class InstagramEmbedParserTest {

    private fun resources(url: String) =
        """[{"src":"$url-1080x1080","config_width":1080,"config_height":1080},""" +
            """{"src":"$url","config_width":1080,"config_height":1440},""" +
            """{"src":"$url-640","config_width":640,"config_height":853}]"""

    private fun context(code: String, media: String) =
        """{"context":{"type":"GraphSidecar","shortcode":"$code"},"gql_data":{"shortcode_media":{"shortcode":"$code",$media}}}"""

    @Test
    fun `sidecar yields the largest full-aspect rendition of each photo and every video`() {
        val json = context(
            "CAR",
            """"__typename":"GraphSidecar","edge_sidecar_to_children":{"edges":[""" +
                """{"node":{"is_video":false,"display_resources":${resources("https://cdn/1.jpg")}}},""" +
                """{"node":{"is_video":true,"video_url":"https://cdn/v.mp4","dimensions":{"width":720,"height":1280},"display_resources":${resources("https://cdn/cover.jpg")}}},""" +
                """{"node":{"is_video":false,"display_resources":${resources("https://cdn/2.jpg")}}}]}""",
        )

        val media = InstagramEmbedParser.parse(json, "CAR")!!

        assertEquals(listOf(MediaImage("https://cdn/1.jpg", 1080, 1440), MediaImage("https://cdn/2.jpg", 1080, 1440)), media.images)
        assertEquals(listOf(listOf(VideoVersion("https://cdn/v.mp4", 720, 1280))), media.videos)
        assertTrue(media.isComplete)
    }

    @Test
    fun `video hidden by the embed marks the media incomplete`() {
        val media = InstagramEmbedParser.parse(
            context("VID", """"is_video":true,"display_resources":${resources("https://cdn/c.jpg")}"""),
            "VID",
        )!!

        assertTrue(media.isEmpty)
        assertEquals(false, media.isComplete)
    }

    @Test
    fun `video post yields its video and not its cover`() {
        val media = InstagramEmbedParser.parse(
            context("VID", """"is_video":true,"video_url":"https://cdn/v.mp4","display_resources":${resources("https://cdn/c.jpg")}"""),
            "VID",
        )!!

        assertTrue(media.images.isEmpty())
        assertEquals("https://cdn/v.mp4", media.videos.single().single().url)
    }

    @Test
    fun `another post, missing media or malformed json yields null`() {
        assertNull(InstagramEmbedParser.parse(context("OTHER", """"is_video":false"""), "MINE"))
        assertNull(InstagramEmbedParser.parse("""{"context":{},"gql_data":null}""", "MINE"))
        assertNull(InstagramEmbedParser.parse("not json", "MINE"))
    }

    @Test
    fun `scans the contextJSON string out of the embed page script`() {
        val contextJson = context("ABC", """"is_video":false,"display_resources":${resources("https://cdn/p.jpg")}""")
        val page = "<html><script>requireLazy([],function(){s.handle({\"isSidecar\":false,\"contextJSON\":" +
            JSONObject.quote(contextJson) + "});});</script></html>"

        assertEquals("https://cdn/p.jpg", InstagramEmbedParser.scan(Buffer().writeUtf8(page), "ABC")!!.images.single().url)
    }
}
