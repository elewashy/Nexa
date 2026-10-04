package com.elewashy.nexa.feature.share.domain

import com.elewashy.nexa.feature.share.domain.model.MediaPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPageClassifierTest {

    private fun guaranteed(url: String, key: String) {
        val page = MediaPageClassifier.classify(url)
        assertEquals(url, MediaPresence.GUARANTEED, page?.presence)
        assertEquals(url, key, page?.contentKey)
    }

    private fun needsCheck(url: String, key: String) {
        val page = MediaPageClassifier.classify(url)
        assertEquals(url, MediaPresence.REQUIRES_PAGE_CHECK, page?.presence)
        assertEquals(url, key, page?.contentKey)
    }

    private fun none(url: String?) = assertNull(url, MediaPageClassifier.classify(url))

    @Test
    fun `youtube video pages are guaranteed media`() {
        guaranteed("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s", "youtube:dQw4w9WgXcQ")
        guaranteed("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ")
        guaranteed("https://music.youtube.com/watch?v=dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ")
        guaranteed("https://youtube.com/shorts/dQw4w9WgXcQ?si=abc", "youtube:dQw4w9WgXcQ")
        guaranteed("https://www.youtube.com/live/dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ")
        guaranteed("https://youtu.be/dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ")
    }

    @Test
    fun `youtube browse pages are not media pages`() {
        none("https://www.youtube.com/")
        none("https://m.youtube.com/feed/subscriptions")
        none("https://www.youtube.com/@channel/videos")
        none("https://www.youtube.com/results?search_query=cats")
        none("https://www.youtube.com/watch")
        none("https://www.youtube.com/watch?v=short")
        none("https://www.youtube.com/shorts/")
    }

    @Test
    fun `instagram posts and reels are guaranteed media`() {
        guaranteed("https://www.instagram.com/p/CxYz123_-a/", "instagram:CxYz123_-a")
        guaranteed("https://www.instagram.com/reel/CxYz123/?igsh=abc", "instagram:CxYz123")
        guaranteed("https://www.instagram.com/reels/CxYz123/", "instagram:CxYz123")
        guaranteed("https://www.instagram.com/tv/CxYz123", "instagram:CxYz123")
        guaranteed("https://www.instagram.com/someone/p/CxYz123/", "instagram:CxYz123")
        guaranteed("https://www.instagram.com/someone/reel/CxYz123/", "instagram:CxYz123")
    }

    @Test
    fun `instagram share links are media pages but never pass their token off as a shortcode`() {
        guaranteed("https://www.instagram.com/share/reel/BAabc123/", "instagram:share:BAabc123")
        guaranteed("https://www.instagram.com/share/p/BAabc123", "instagram:share:BAabc123")
    }

    @Test
    fun `content ids are the platform identifiers`() {
        assertEquals("CxYz123", MediaPageClassifier.classify("https://www.instagram.com/reel/CxYz123/?igsh=1")?.contentId)
        assertEquals("2106095256093766131", MediaPageClassifier.classify("https://x.com/u/status/2106095256093766131/photo/1")?.contentId)
        assertEquals("Abc", MediaPageClassifier.classify("https://www.threads.com/@u/post/Abc")?.contentId)
        assertEquals("1234567890", MediaPageClassifier.classify("https://www.facebook.com/watch/?v=1234567890")?.contentId)
        assertEquals("dQw4w9WgXcQ", MediaPageClassifier.classify("https://youtu.be/dQw4w9WgXcQ")?.contentId)
    }

    @Test
    fun `instagram feeds and profiles are not media pages`() {
        none("https://www.instagram.com/")
        none("https://www.instagram.com/someone/")
        none("https://www.instagram.com/reels/")
        none("https://www.instagram.com/explore/")
        none("https://www.instagram.com/a/b/p/CxYz123/")
    }

    @Test
    fun `tiktok videos photo posts and short links are guaranteed media`() {
        guaranteed("https://www.tiktok.com/@user/video/7300000000000000000?lang=en", "tiktok:7300000000000000000")
        guaranteed("https://www.tiktok.com/@user/photo/7300000000000000001", "tiktok:7300000000000000001")
        guaranteed("https://vm.tiktok.com/ZMabc123/", "tiktok:short:ZMabc123")
        guaranteed("https://vt.tiktok.com/ZSabc123/", "tiktok:short:ZSabc123")
        guaranteed("https://www.tiktok.com/t/ZTabc123/", "tiktok:short:ZTabc123")
    }

    @Test
    fun `tiktok feeds and profiles are not media pages`() {
        none("https://www.tiktok.com/")
        none("https://www.tiktok.com/foryou")
        none("https://www.tiktok.com/@user")
        none("https://www.tiktok.com/@user/video/notanid")
    }

    @Test
    fun `tweets need a page check and share one key across hosts and media sub-pages`() {
        val key = "twitter:2106095256093766131"
        needsCheck("https://x.com/_GauravGosain/status/2106095256093766131", key)
        needsCheck("https://twitter.com/_GauravGosain/status/2106095256093766131?s=20", key)
        needsCheck("https://mobile.twitter.com/_GauravGosain/status/2106095256093766131", key)
        needsCheck("https://x.com/_GauravGosain/status/2106095256093766131/photo/1", key)
        needsCheck("https://x.com/_GauravGosain/status/2106095256093766131/video/1", key)
        needsCheck("https://x.com/i/web/status/2106095256093766131", key)
    }

    @Test
    fun `x pages other than tweets are not media pages`() {
        none("https://x.com/")
        none("https://x.com/home")
        none("https://x.com/explore")
        none("https://x.com/_GauravGosain")
        none("https://x.com/_GauravGosain/media")
        none("https://x.com/search?q=video")
        none("https://x.com/user/status/notanid")
    }

    @Test
    fun `threads posts need a page check`() {
        needsCheck("https://www.threads.net/@user/post/CxYz123", "threads:CxYz123")
        needsCheck("https://www.threads.com/@user/post/CxYz123/?xmt=1", "threads:CxYz123")
        none("https://www.threads.net/")
        none("https://www.threads.net/@user")
    }

    @Test
    fun `facebook videos and photos are guaranteed media`() {
        guaranteed("https://www.facebook.com/watch/?v=1234567890", "facebook:video:1234567890")
        guaranteed("https://m.facebook.com/watch?v=1234567890", "facebook:video:1234567890")
        guaranteed("https://www.facebook.com/reel/1234567890", "facebook:video:1234567890")
        guaranteed("https://www.facebook.com/page/videos/1234567890/", "facebook:video:1234567890")
        guaranteed("https://www.facebook.com/share/v/AbC123/", "facebook:share:AbC123")
        guaranteed("https://www.facebook.com/share/r/AbC123/", "facebook:share:AbC123")
        guaranteed("https://fb.watch/AbC123/", "facebook:watch:AbC123")
        guaranteed("https://www.facebook.com/photo/?fbid=1234567890&set=a.1", "facebook:photo:1234567890")
        guaranteed("https://www.facebook.com/photo.php?fbid=1234567890", "facebook:photo:1234567890")
        guaranteed("https://www.facebook.com/page/photos/a.1/1234567890/", "facebook:photo:1234567890")
    }

    @Test
    fun `facebook posts need a page check and feeds are not media pages`() {
        needsCheck("https://www.facebook.com/page/posts/pfbid0abc", "facebook:post:pfbid0abc")
        needsCheck("https://www.facebook.com/share/p/AbC123/", "facebook:share:AbC123")
        needsCheck("https://m.facebook.com/story.php?story_fbid=123&id=4", "facebook:post:123")
        needsCheck("https://www.facebook.com/permalink.php?story_fbid=123&id=4", "facebook:post:123")
        none("https://www.facebook.com/")
        none("https://www.facebook.com/watch/")
        none("https://www.facebook.com/someone")
        none("https://www.facebook.com/groups/123")
    }

    @Test
    fun `unsupported or malformed urls are not media pages`() {
        none(null)
        none("")
        none("about:blank")
        none("file:///sdcard/video.mp4")
        none("https://example.com/watch?v=dQw4w9WgXcQ")
        none("https://notx.com/user/status/123")
        none("https://x.com/user/status/1 2")
        none("not a url")
    }
}
