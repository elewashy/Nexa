package com.elewashy.nexa.feature.browser.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.annotation.WorkerThread
import kotlin.math.max

/** An image carried inline by a `data:` URL: its encoded bytes and the format they decode as. */
class InlineImage(val mimeType: String, val bytes: ByteArray)

/**
 * Parses and decodes inline `data:<mime>;base64,<payload>` images.
 *
 * Decoding is blocking and proportional to the payload size, so everything except
 * [isBase64Image] must run off the main thread.
 */
object DataUrlImage {

    private const val SCHEME = "data:"
    private const val BASE64_MARKER = ";base64,"

    /** True for inline base64 data URLs; a substring match on "base64" also caught plain URLs. */
    fun isBase64Image(url: String?): Boolean =
        url != null && url.startsWith(SCHEME) && url.contains(BASE64_MARKER)

    /**
     * Returns the encoded image without re-encoding it, or `null` when the payload is not valid
     * base64 or not an image format the platform can decode. The MIME type is the one the bytes
     * actually decode as, which is more reliable than the header the page declared.
     */
    @WorkerThread
    fun parse(dataUrl: String): InlineImage? {
        val bytes = payload(dataUrl) ?: return null
        val bounds = readBounds(bytes) ?: return null
        val mimeType = bounds.outMimeType ?: return null
        return InlineImage(mimeType, bytes)
    }

    /**
     * Decodes the image for display, subsampled so neither side exceeds [maxDimension] pixels,
     * which keeps a page-supplied multi-megapixel image from exhausting the heap.
     */
    @WorkerThread
    fun decode(dataUrl: String, maxDimension: Int): Bitmap? {
        val bytes = payload(dataUrl) ?: return null
        val bounds = readBounds(bytes) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Smallest power-of-two subsampling that fits the larger side within [maxDimension]. */
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        require(maxDimension > 0) { "maxDimension must be positive" }
        val largest = max(width, height)
        var sample = 1
        while (largest / sample > maxDimension) sample *= 2
        return sample
    }

    private fun payload(dataUrl: String): ByteArray? {
        val markerEnd = dataUrl.indexOf(BASE64_MARKER).takeIf { it >= 0 }?.plus(BASE64_MARKER.length)
            ?: return null
        return try {
            Base64.decode(dataUrl.substring(markerEnd), Base64.DEFAULT).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readBounds(bytes: ByteArray): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }
}
