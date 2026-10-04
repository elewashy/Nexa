package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenGraphTest {

    @Test
    fun `og image is read from allowed hosts only`() {
        val html = """<meta property="og:image" content="https://scontent.fbcdn.net/p.jpg?a=1&amp;b=2">""" +
            """<meta property="og:image:width" content="1080"><meta content="1350" property="og:image:height">"""

        assertEquals(MediaImage("https://scontent.fbcdn.net/p.jpg?a=1&b=2", 1080, 1350), OpenGraph.parse(html).image(setOf("fbcdn.net")))
        assertNull(OpenGraph.parse(html).image(setOf("cdninstagram.com")))
        assertNull(OpenGraph.parse("""<meta property="og:image" content="https://evilfbcdn.net/x.jpg">""").image(setOf("fbcdn.net")))
        assertNull(OpenGraph.parse("""<meta property="og:image" content="http://scontent.fbcdn.net/x.jpg">""").image(setOf("fbcdn.net")))
    }

    @Test
    fun `og video is detected`() {
        assertTrue(OpenGraph.parse("""<meta property='og:video' content='https://cdn/v.mp4'>""").hasVideo)
        assertFalse(OpenGraph.parse("""<meta property="og:image" content="https://cdn/p.jpg">""").hasVideo)
    }

    @Test
    fun `only the head is read and the first occurrence of a property wins`() {
        val html = """<html><head><meta property="OG:IMAGE" content="https://a.fbcdn.net/first.jpg">""" +
            """<meta property="og:image" content="https://a.fbcdn.net/second.jpg"></head>""" +
            """<body><meta property="og:video" content="https://cdn/in-body.mp4"></body></html>"""
        val openGraph = OpenGraph.parse(html)

        assertEquals("https://a.fbcdn.net/first.jpg", openGraph.image(setOf("fbcdn.net"))?.url)
        assertFalse("meta tags after </head> are not page metadata", openGraph.hasVideo)
    }
}
