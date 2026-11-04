package com.elewashy.nexa.feature.browser.data.image

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Real image codecs: the legacy shadow BitmapFactory does not decode bytes.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class DataUrlImageTest {

    @Test
    fun `only base64 data URLs are inline images`() {
        assertTrue(DataUrlImage.isBase64Image("data:image/png;base64,AAAA"))
        assertFalse(DataUrlImage.isBase64Image(null))
        assertFalse(DataUrlImage.isBase64Image("https://example.com/base64/image.png"))
        assertFalse(DataUrlImage.isBase64Image("data:image/svg+xml,<svg/>"))
    }

    @Test
    fun `parse keeps the original bytes and reports the decoded format`() {
        val png = encode(Bitmap.CompressFormat.PNG)

        // The declared type is deliberately wrong: the decoded format must win.
        val image = DataUrlImage.parse(dataUrl("image/jpeg", png))

        assertNotNull(image)
        assertEquals("image/png", image!!.mimeType)
        assertArrayEquals(png, image.bytes)
    }

    @Test
    fun `parse rejects payloads that are not decodable images`() {
        assertNull(DataUrlImage.parse(dataUrl("image/png", "not an image".toByteArray())))
        assertNull(DataUrlImage.parse("data:image/png;base64,%%%not-base64%%%"))
        assertNull(DataUrlImage.parse("data:image/png;base64,"))
    }

    @Test
    fun `decode returns a bitmap no larger than the requested bound`() {
        val bitmap = DataUrlImage.decode(dataUrl("image/png", encode(Bitmap.CompressFormat.PNG)), 2)

        assertNotNull(bitmap)
        assertTrue(maxOf(bitmap!!.width, bitmap.height) <= 2)
    }

    @Test
    fun `sample size is the smallest power of two that fits`() {
        assertEquals(1, DataUrlImage.sampleSize(width = 2048, height = 1024, maxDimension = 2048))
        assertEquals(2, DataUrlImage.sampleSize(width = 2049, height = 1024, maxDimension = 2048))
        assertEquals(4, DataUrlImage.sampleSize(width = 1000, height = 8000, maxDimension = 2048))
        assertEquals(1, DataUrlImage.sampleSize(width = 0, height = 0, maxDimension = 2048))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sample size rejects a non-positive bound`() {
        DataUrlImage.sampleSize(width = 10, height = 10, maxDimension = 0)
    }

    private fun encode(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(format, 100, out)
            out.toByteArray()
        }
    }

    private fun dataUrl(mimeType: String, bytes: ByteArray) =
        "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
