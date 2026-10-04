package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterSyndicationTest {

    @Test
    fun `photos are requested in original quality with their dimensions`() {
        val tweet = TwitterSyndication.parse(
            """{"__typename":"Tweet","mediaDetails":[
                {"type":"photo","media_url_https":"https://pbs.twimg.com/media/AAA.jpg","original_info":{"width":4096,"height":2048}},
                {"type":"photo","media_url_https":"https://pbs.twimg.com/media/BBB.png","original_info":{"width":800,"height":600}}
            ]}"""
        )!!

        assertEquals(
            listOf(
                MediaImage("https://pbs.twimg.com/media/AAA?format=jpg&name=orig", 4096, 2048),
                MediaImage("https://pbs.twimg.com/media/BBB?format=png&name=orig", 800, 600),
            ),
            tweet.photos,
        )
        assertFalse(tweet.hasVideo)
    }

    @Test
    fun `videos and gifs are flagged and their thumbnails are not offered as photos`() {
        val tweet = TwitterSyndication.parse(
            """{"__typename":"Tweet","mediaDetails":[
                {"type":"video","media_url_https":"https://pbs.twimg.com/amplify_video_thumb/1/img/x.jpg"},
                {"type":"animated_gif","media_url_https":"https://pbs.twimg.com/tweet_video_thumb/y.jpg"}
            ]}"""
        )!!

        assertTrue(tweet.hasVideo)
        assertTrue(tweet.photos.isEmpty())
    }

    @Test
    fun `progressive mp4 renditions are read best first, hls playlists skipped`() {
        // Shape of the real response for x.com/_GauravGosain/status/2106095256093766131.
        val tweet = TwitterSyndication.parse(
            """{"__typename":"Tweet","mediaDetails":[{"type":"video","original_info":{"width":1920,"height":1080},
                "video_info":{"variants":[
                  {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/amplify_video/1/pl/a.m3u8?tag=14"},
                  {"content_type":"video/mp4","bitrate":288000,"url":"https://video.twimg.com/amplify_video/1/vid/avc1/480x270/a.mp4?tag=14"},
                  {"content_type":"video/mp4","bitrate":2176000,"url":"https://video.twimg.com/amplify_video/1/vid/avc1/1280x720/c.mp4?tag=14"},
                  {"content_type":"video/mp4","bitrate":832000,"url":"https://video.twimg.com/amplify_video/1/vid/avc1/640x360/b.mp4?tag=14"}]}},
              {"type":"animated_gif","original_info":{"width":498,"height":280},
                "video_info":{"variants":[{"content_type":"video/mp4","bitrate":0,"url":"https://video.twimg.com/tweet_video/G.mp4"}]}}]}"""
        )!!

        assertTrue(tweet.hasVideo)
        assertEquals(
            listOf(
                TweetVideoVariant("https://video.twimg.com/amplify_video/1/vid/avc1/1280x720/c.mp4?tag=14", 2176000, 1280, 720),
                TweetVideoVariant("https://video.twimg.com/amplify_video/1/vid/avc1/640x360/b.mp4?tag=14", 832000, 640, 360),
                TweetVideoVariant("https://video.twimg.com/amplify_video/1/vid/avc1/480x270/a.mp4?tag=14", 288000, 480, 270),
            ),
            tweet.videos[0].variants,
        )
        assertEquals("GIF size falls back to the media's", listOf(TweetVideoVariant("https://video.twimg.com/tweet_video/G.mp4", 0, 498, 280)), tweet.videos[1].variants)
    }

    @Test
    fun `quality options label the first video by resolution and number the others`() {
        val options = TwitterVideoExtractor.videoOptions(
            listOf(
                TweetVideo(listOf(TweetVideoVariant("https://v/720.mp4", 2, 1280, 720), TweetVideoVariant("https://v/360.mp4", 1, 640, 360))),
                TweetVideo(listOf(TweetVideoVariant("https://v/2-720.mp4", 2, 720, 1280), TweetVideoVariant("https://v/2-x.mp4", 1, 0, 0))),
            )
        )

        assertEquals(
            mapOf(
                "1280x720" to "https://v/720.mp4",
                "640x360" to "https://v/360.mp4",
                "Video 2 (720p)" to "https://v/2-720.mp4",
                "Video 2 (Quality_2)" to "https://v/2-x.mp4",
            ),
            options,
        )
    }

    @Test
    fun `legacy photos array is used without media details`() {
        val tweet = TwitterSyndication.parse(
            """{"__typename":"Tweet","photos":[{"url":"https://pbs.twimg.com/media/CCC.jpg","width":10,"height":20}]}"""
        )!!

        assertEquals(listOf(MediaImage("https://pbs.twimg.com/media/CCC?format=jpg&name=orig", 10, 20)), tweet.photos)
    }

    @Test
    fun `text-only tweets have no media`() {
        val tweet = TwitterSyndication.parse("""{"__typename":"Tweet","text":"hello"}""")!!
        assertTrue(tweet.photos.isEmpty())
        assertFalse(tweet.hasVideo)
        assertFalse(tweet.hasMedia)
    }

    @Test
    fun `unavailable or malformed tweets yield null`() {
        assertNull(TwitterSyndication.parse("""{"__typename":"TweetTombstone"}"""))
        assertNull(TwitterSyndication.parse("{}"))
        assertNull(TwitterSyndication.parse("<html>"))
    }

    @Test
    fun `original quality leaves non-photo or already-sized urls alone`() {
        assertEquals("https://pbs.twimg.com/media/X?format=jpg&name=small", TwitterSyndication.originalQuality("https://pbs.twimg.com/media/X?format=jpg&name=small"))
        assertEquals("https://example.com/a.jpg", TwitterSyndication.originalQuality("https://example.com/a.jpg"))
    }

    @Test
    fun `token matches the embed widget formula`() {
        // ((2106095256093766131 / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '')
        assertEquals("53shrfc66vg7h4", TwitterSyndication.token("2106095256093766131"))
        assertEquals("0", TwitterSyndication.token("not-a-number"))
        assertTrue(TwitterSyndication.requestUrl("20").startsWith("https://cdn.syndication.twimg.com/tweet-result?id=20&token="))
    }
}
