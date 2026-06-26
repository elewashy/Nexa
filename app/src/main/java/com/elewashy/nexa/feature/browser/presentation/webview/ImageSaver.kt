package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * ImageSaver — Saves / shares images via the MediaStore API.
 *
 * Uses `IS_PENDING` on Android Q+ to guarantee an atomic write
 * that other apps can observe only after the image is fully committed.
 *
 * Quality is set to 85 % JPEG — a good balance between file size
 * and visual fidelity on mobile screens.
 */
object ImageSaver {

    private const val JPEG_QUALITY = 85
    private const val SUBFOLDER = "Nexa"

    /**
     * Saves [bitmap] to the shared Pictures/Nexa directory.
     *
     * @return The content URI of the newly created image, or `null` on failure.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val contentValues = buildContentValues(fileName)
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null

            val saved = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } ?: false

            if (!saved) {
                resolver.delete(uri, null, null)
                return null
            }

            markNotPending(resolver, uri, contentValues)
            uri
        } catch (_: Exception) {
            null
        }
    }

    // ── Private helpers ────────────────────────────────────────

    private fun buildContentValues(fileName: String) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    private fun markNotPending(
        resolver: android.content.ContentResolver,
        uri: Uri,
        contentValues: ContentValues
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
    }
}
