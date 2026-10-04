package com.elewashy.nexa.feature.share.domain.model

import java.net.URI

/**
 * A downloadable image of a shared post (a photo, or one slide of a
 * carousel / photo post), in the best resolution the platform exposes.
 *
 * [width] and [height] are 0 when the platform does not report them.
 */
data class MediaImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** File format to save the image as, read from its URL. */
    val format: ImageFormat get() = ImageFormat.fromUrl(url)
}

enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif"),
    HEIC("heic", "image/heic"),
    AVIF("avif", "image/avif");

    companion object {
        /**
         * Reads the format from a `format=` query parameter (X's image CDN)
         * or the path extension; CDN URLs without either are JPEG.
         */
        fun fromUrl(url: String): ImageFormat {
            val uri = runCatching { URI(url) }.getOrNull() ?: return JPEG
            val declared = uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("format=") }
                ?.substringAfter('=')
                ?: uri.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "")
            return fromExtension(declared) ?: JPEG
        }

        private fun fromExtension(extension: String): ImageFormat? = when (extension.lowercase()) {
            "jpg", "jpeg" -> JPEG
            else -> entries.firstOrNull { it.extension == extension.lowercase() }
        }
    }
}
