package com.elewashy.nexa.core.files

import android.os.Environment
import java.io.File

/**
 * Single definition of where Nexa writes downloaded files.
 *
 * Every layer that touches downloaded files (engine, repository, UI open/share
 * actions, orphan sweeps) resolves paths through this object so the directory
 * and the containment rule can never drift apart.
 */
object DownloadDirectory {
    private const val FOLDER_NAME = "Nexa"

    /** `Downloads/Nexa` on shared storage. Not guaranteed to exist. */
    fun root(): File = File(publicDownloads(), FOLDER_NAME)

    /** [root], creating it when missing. */
    fun ensureRoot(): File = root().also { if (!it.exists()) it.mkdirs() }

    /** The shared public Downloads directory that backs [root]. */
    fun publicDownloads(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * Canonicalises [path] and returns it only when it lives inside [root].
     * Persisted paths are untrusted input for file operations: a record that
     * points outside the download directory must never be opened, shared,
     * scanned, or deleted.
     */
    fun resolveOwnedFile(path: String): File? = try {
        val rootPath = root().canonicalFile.path
        val file = File(path).canonicalFile
        if (file.path.startsWith(rootPath + File.separator)) file else null
    } catch (_: Exception) {
        null
    }
}
