package com.elewashy.nexa.feature.share

import com.elewashy.nexa.feature.share.domain.model.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaImageTest {

    @Test
    fun `format comes from the format parameter or the path extension`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromUrl("https://pbs.twimg.com/media/A?format=jpg&name=orig"))
        assertEquals(ImageFormat.PNG, ImageFormat.fromUrl("https://pbs.twimg.com/media/A?format=png&name=orig"))
        assertEquals(ImageFormat.WEBP, ImageFormat.fromUrl("https://scontent.cdninstagram.com/v/t51/1_n.webp?stp=dst-jpg"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromUrl("https://p16-sign.tiktokcdn.com/obj/x~tplv-photomode-image.jpeg?x-expires=1"))
        assertEquals(ImageFormat.HEIC, ImageFormat.fromUrl("https://cdn.example.com/a.HEIC"))
    }

    @Test
    fun `unknown or missing formats default to jpeg`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromUrl("https://cdn.example.com/image"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromUrl("https://cdn.example.com/image.php?id=1"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromUrl("not a url"))
    }
}
