package com.elewashy.nexa.feature.downloads.data.filename

import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Local filename handling only; never issues a network request. */
object FileNameResolver {
    private const val MAX_NAME_BYTES = 250 // The adjacent in-progress name adds ".part".
    private val quotedEscape = Regex("\\\\(.)")
    private val parameters = Regex("(?:^|;)\\s*(filename\\*?)\\s*=\\s*(\"(?:\\\\.|[^\"])*\"|[^;]*)", RegexOption.IGNORE_CASE)

    fun fromSource(url: String, disposition: String?): String =
        disposition?.let(::extractFilenameFromContentDisposition)
            ?: url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotEmpty() }
            ?: "download"

    @Suppress("UNUSED_PARAMETER")
    fun sanitise(originalName: String, contentType: String?): String {
        val safe = originalName.map { if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it }.joinToString("")
        val name = safe.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "download"
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val extension = name.substring(dot).takeIf { it.toByteArray().size < 64 }.orEmpty()
        val base = name.substring(0, name.length - extension.length)
        var end = 0
        var bytes = extension.toByteArray().size
        while (end < base.length) {
            val count = Character.charCount(base.codePointAt(end))
            val size = base.substring(end, end + count).toByteArray().size
            if (bytes + size > MAX_NAME_BYTES) break
            bytes += size
            end += count
        }
        return base.substring(0, end) + extension
    }

    fun sanitiseWithForcedExtension(originalName: String, forcedExtension: String): String {
        val name = sanitise(originalName, null)
        val extension = forcedExtension.trimStart('.').filter(Char::isLetterOrDigit)
        require(extension.isNotEmpty()) { "Invalid extension" }
        val dot = name.lastIndexOf('.')
        return sanitise((if (dot > 0) name.substring(0, dot) else name) + ".$extension", null)
    }

    fun uniqueName(directory: File, fileName: String, reservedNames: Set<String> = emptySet()): String {
        fun taken(name: String) = name in reservedNames || File(directory, name).exists() || File(directory, "$name.part").exists()
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val base = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        var index = 1L
        while (true) {
            val suffix = "_$index"
            val candidate = sanitise(base + suffix + extension, null)
            // Leave room for the suffix even at the filesystem byte limit.
            val bounded = if (candidate.endsWith(suffix + extension)) candidate else {
                var shortened = base
                while ((shortened + suffix + extension).toByteArray().size > MAX_NAME_BYTES) shortened = shortened.dropLast(Character.charCount(shortened.codePointBefore(shortened.length)))
                shortened + suffix + extension
            }
            if (!taken(bounded)) return bounded
            index++
        }
    }

    internal fun extractFilenameFromContentDisposition(header: String): String? {
        var ordinary: String? = null
        for (match in parameters.findAll(header)) {
            val key = match.groupValues[1]
            val raw = match.groupValues[2].trim().let {
                if (it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1).replace(quotedEscape, "$1") else it
            }
            if (key.endsWith('*')) {
                val parts = raw.split('\'', limit = 3)
                if (parts.size == 3) try {
                    val charset = Charset.forName(parts[0])
                    val decoded = URLDecoder.decode(parts[2].replace("+", "%2B"), charset.name())
                    if (decoded.isNotEmpty()) return decoded
                } catch (_: IllegalArgumentException) { }
            } else if (ordinary == null && raw.isNotEmpty()) ordinary = raw
        }
        return ordinary
    }
}
