package com.elewashy.nexa.feature.downloads.data.filename

import android.util.Log
import android.webkit.MimeTypeMap
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.downloads.data.engine.HttpProber
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import okhttp3.OkHttpClient
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

    @Volatile
    private var clientProvider: HttpClientProvider? = null

    /**
     * Wired once at app startup (NexaApp): this object is referenced statically
     * and cannot be Hilt-injected, so the shared provider is handed in here.
     */
    fun installSharedClientProvider(provider: HttpClientProvider) {
        clientProvider = provider
    }

    // Pre-compiled regex patterns (allocated once)

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

    // Optimised for fast HEAD probes: tight timeouts + an overall call cap.
    // Derived from the shared provider so probes reuse the app-wide pool.
    private val httpClient: OkHttpClient by lazy {
        (clientProvider?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ===================================================================
    //  Public API
    // ===================================================================

    /**
     * Single shared probe pass over [url]. Returns the full [HttpProber.ProbeResult]
     * (size, range support, content type, filename candidate, final URL) so callers
     * can resolve the filename AND plan the download from one request — probing the
     * same URL twice could burn one-time signed URLs on the GET fallback.
     */
    suspend fun probeOnce(
        url: String,
        userAgent: String?,
        referer: String?,
        cookies: String?
    ): HttpProber.ProbeResult {
        val headers = buildMap {
            put("User-Agent", userAgent ?: "Mozilla/5.0")
            referer?.let { put("Referer", it) }
            cookies?.let { put("Cookie", it) }
            put("Accept", "*/*")
            // Match the engine's probe semantics — never let OkHttp rewrite the
            // response (transparent gzip) between probing and downloading.
            put("Accept-Encoding", "identity")
        }
        return try {
            HttpProber.probe(httpClient, url, headers)
        } catch (e: Exception) {
            Log.w(TAG, "Server probe failed (using fallback): ${e.message}")
            HttpProber.ProbeResult()
        }
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
     * A leftover `.part` partial file also counts as a collision, as does any
     * name in [reservedNames] — names reserved in memory by concurrent
     * in-flight downloads whose `.part` file does not exist on disk yet.
     */
    fun uniqueName(
        directory: File,
        fileName: String,
        reservedNames: Set<String> = emptySet()
    ): String {
        if (!nameTaken(directory, fileName) && fileName !in reservedNames) return fileName

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
            if (!nameTaken(directory, candidate) && candidate !in reservedNames) return candidate
        }

        // Safety fallback
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private fun nameTaken(directory: File, name: String): Boolean =
        File(directory, name).exists() || File(directory, "$name.part").exists()

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
