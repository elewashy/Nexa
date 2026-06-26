package com.elewashy.nexa.feature.downloads.data.filename

import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import java.util.concurrent.TimeUnit

/**
 * Resolves filenames for downloads by:
 * 1. Making a HEAD request to the server to obtain Content-Disposition / Content-Type
 * 2. Sanitising the result so it is filesystem-safe
 * 3. Generating a unique name when the target already exists
 *
 * Thread-safe — all public functions are either pure or run on [Dispatchers.IO].
 */
object FileNameResolver {

    private const val TAG = "FileNameResolver"

    // ----- Pre-compiled regex patterns (allocated once) -----

    private val FILENAME_STAR_RE =
        Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE)

    private val FILENAME_RE =
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)

    private val UNSAFE_FS_CHARS_RE = Regex("[\\\\/:*?\"<>|]")

    private val CONTROL_CHARS_RE = Regex("[\\p{Cntrl}]")

    private val MULTI_UNDERSCORE_RE = Regex("__+")

    private val EXTENSION_CLEAN_RE = Regex("[^a-zA-Z0-9]")

    private val RESERVED_WINDOWS_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    // Lazy OkHttpClient – optimised for fast HEAD requests only
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ===================================================================
    //  Public API
    // ===================================================================

    /**
     * Resolves filename + content-type by probing the server.
     * Starts with a fast HEAD request, then fallbacks to a GET (Range) if HEAD is blocked.
     *
     * @return Pair(filename, contentType) — either or both may be null.
     */
    suspend fun fetchFilenameFromServer(
        url: String,
        userAgent: String?,
        referer: String?,
        cookies: String?
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        var fileName: String? = null
        var contentType: String? = null
 
        try {
            Log.d(TAG, "Probing server → $url")
 
            val baseRequest = OkHttpRequest.Builder()
                .url(url)
                .cacheControl(CacheControl.Builder().noCache().build())
                .apply {
                    addHeader("User-Agent", userAgent ?: "Mozilla/5.0")
                    referer?.let { addHeader("Referer", it) }
                    cookies?.let { addHeader("Cookie", it) }
                    addHeader("Accept", "*/*")
                }
                .build()
 
            // Phase 1: Try HEAD (fastest)
            val headRequest = baseRequest.newBuilder().head().build()
            val startMs = System.currentTimeMillis()
 
            httpClient.newCall(headRequest).execute().use { headResponse ->
                Log.d(TAG, "HEAD ${headResponse.code} in ${System.currentTimeMillis() - startMs}ms")
 
                if (headResponse.isSuccessful) {
                    processResponse(headResponse, url).let { (name, type) ->
                        fileName = name
                        contentType = type
                    }
                } else {
                    // Phase 2: Try GET with Range if HEAD failed (common for protected video hosts)
                    Log.d(TAG, "HEAD failed, falling back to GET Range...")
                    val getRequest = baseRequest.newBuilder()
                        .get()
                        .addHeader("Range", "bytes=0-0")
                        .build()
                        
                    httpClient.newCall(getRequest).execute().use { getResponse ->
                        Log.d(TAG, "GET (Range) ${getResponse.code}")
                        processResponse(getResponse, url).let { (name, type) ->
                            fileName = name
                            contentType = type
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server probe failed (using fallback): ${e.message}")
        }
 
        Pair(fileName, contentType)
    }
 
    /** Internal helper to extract metadata from a successful response (HEAD or GET). */
    private fun processResponse(response: okhttp3.Response, url: String): Pair<String?, String?> {
        if (!response.isSuccessful && response.code != 206) return Pair(null, null)
 
        val type = response.header("Content-Type")?.substringBefore(';')
        var name: String? = null
 
        val cd = response.header("Content-Disposition")
        if (cd != null) {
            name = extractFilenameFromContentDisposition(cd)
            if (name != null) Log.d(TAG, "Filename from Disposition: $name")
        }
 
        if (name == null) {
            val urlSegment = url.substringAfterLast('/')
                .substringBefore('?')
                .substringBefore('#')
            if (urlSegment.isNotEmpty() && urlSegment.contains('.')) {
                name = decodePercent(urlSegment)
                Log.d(TAG, "Filename from URL: $name")
            }
        }
 
        return Pair(name, type)
    }

    /**
     * Cleans [originalName] so it is filesystem-safe and has a reasonable extension.
     * If [contentType] is non-null and the name lacks an extension, one is derived
     * from the MIME type.
     */
    fun sanitise(originalName: String, contentType: String?): String {
        var name = decodePercent(originalName)
        var extension = ""

        // Remove dangerous characters
        name = UNSAFE_FS_CHARS_RE.replace(name, "_")

        // Split base / extension
        val lastDot = name.lastIndexOf('.')
        if (lastDot > 0 && lastDot < name.length - 1) {
            extension = name.substring(lastDot + 1)
            name = name.substring(0, lastDot)
        }

        // Normalise base name
        name = normaliseBaseName(name)

        // Derive extension from MIME type when missing
        if (extension.isEmpty() && contentType != null) {
            MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(contentType)
                ?.let { extension = it }
        }

        // Fallback extension
        if (extension.isEmpty() && name.isNotEmpty() && !name.contains(".")) {
            if (contentType == null || contentType == "application/octet-stream") {
                if (!originalName.contains('.') || originalName.endsWith(".")) {
                    extension = "bin"
                }
            }
        }

        extension = EXTENSION_CLEAN_RE.replace(extension, "").take(5).lowercase()
        val baseName = name.ifEmpty { "download" }

        return if (extension.isNotEmpty()) "$baseName.$extension" else baseName
    }

    /**
     * Same as [sanitise] but forces to [forcedExtension], ignoring MIME type.
     * Used for YouTube audio (force .mp3) etc.
     */
    fun sanitiseWithForcedExtension(originalName: String, forcedExtension: String): String {
        var name = decodePercent(originalName)
        name = UNSAFE_FS_CHARS_RE.replace(name, "_")

        // Remove existing extension
        val lastDot = name.lastIndexOf('.')
        if (lastDot > 0 && lastDot < name.length - 1) {
            name = name.substring(0, lastDot)
        }

        name = normaliseBaseName(name)

        val baseName = name.ifEmpty { "download" }
        val cleanExt = EXTENSION_CLEAN_RE.replace(forcedExtension, "").take(5).lowercase()

        return "$baseName.$cleanExt"
    }

    private fun normaliseBaseName(raw: String): String {
        var name = CONTROL_CHARS_RE.replace(raw, "_")
            .replace('.', '_')
            .replace(' ', '_')
        name = MULTI_UNDERSCORE_RE.replace(name, "_")
            .trim('_', '.', ' ')
            .take(120)
        if (name.equals(".") || name.equals("..")) name = "download"
        if (RESERVED_WINDOWS_NAMES.contains(name.uppercase())) name = "download_$name"
        return name
    }

    /**
     * Returns a unique file name inside [directory].
     * Appends `_1`, `_2`, … if the name already exists (caps at 1000 → timestamp fallback).
     */
    fun uniqueName(directory: File, fileName: String): String {
        if (!File(directory, fileName).exists()) return fileName

        val lastDot = fileName.lastIndexOf('.')
        val base: String
        val ext: String
        if (lastDot > 0 && lastDot < fileName.length - 1) {
            base = fileName.substring(0, lastDot)
            ext = fileName.substring(lastDot) // includes '.'
        } else {
            base = fileName
            ext = ""
        }

        for (i in 1..1000) {
            val candidate = "${base}_$i$ext"
            if (!File(directory, candidate).exists()) return candidate
        }

        // Safety fallback
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    // ===================================================================
    //  Internal helpers
    // ===================================================================

    /**
     * Extracts filename from a Content-Disposition header.
     * Handles `filename*=UTF-8''…` (RFC 5987) and `filename="…"`.
     */
    internal fun extractFilenameFromContentDisposition(header: String): String? {
        try {
            FILENAME_STAR_RE.find(header)?.groupValues?.get(1)?.let { raw ->
                return URLDecoder.decode(raw.trim('"', '\''), "UTF-8")
            }

            FILENAME_RE.find(header)?.groupValues?.get(1)?.let { raw ->
                return URLDecoder.decode(raw.trim('"', '\''), "UTF-8")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Content-Disposition: ${e.message}")
        }
        return null
    }

    /** Decodes percent-encoded strings; returns [text] as-is on failure. */
    private fun decodePercent(text: String): String {
        if (!text.contains('%')) return text
        return try {
            URLDecoder.decode(text, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            text
        }
    }
}
