package com.elewashy.nexa.feature.browser.data.image

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import com.elewashy.nexa.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Writes inline (`data:`) images to the shared Pictures/Nexa collection through MediaStore.
 *
 * The original encoded bytes are stored as-is, in their own format: no decode/re-encode, so
 * there is no quality loss and transparency survives. On Android 10+ the entry stays
 * `IS_PENDING` until fully written, so other apps never observe a partial file.
 */
@Singleton
class InlineImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Saves the image under [baseName]; returns its content URI, or `null` on failure. */
    suspend fun saveToGallery(dataUrl: String, baseName: String): Uri? =
        withContext(ioDispatcher) { writeToGallery(dataUrl, baseName) }

    @WorkerThread
    private fun writeToGallery(dataUrl: String, baseName: String): Uri? {
        val image = DataUrlImage.parse(dataUrl) ?: return null
        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pendingEntry(image, baseName))
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null

        return if (write(resolver, uri, image.bytes) && publish(resolver, uri)) {
            uri
        } else {
            discard(resolver, uri)
            null
        }
    }

    private fun pendingEntry(image: InlineImage, baseName: String) = ContentValues().apply {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(image.mimeType)
        put(MediaStore.Images.Media.DISPLAY_NAME, extension?.let { "$baseName.$it" } ?: baseName)
        put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    @WorkerThread
    private fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean = try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    @WorkerThread
    private fun publish(resolver: ContentResolver, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        return try {
            resolver.update(uri, published, null, null) > 0
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    @WorkerThread
    private fun discard(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: SecurityException) {
            // Nothing else to clean up: a pending entry is purged by MediaStore after a week.
        }
    }

    private companion object {
        const val SUBFOLDER = "Nexa"
    }
}
