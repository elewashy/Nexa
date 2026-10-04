package com.elewashy.nexa.feature.adblock.data.resources

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.elewashy.nexa.core.network.HttpClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and caches remote browser resources (filter lists).
 *
 * Guarantees:
 *  - **No unnecessary downloads**: a resource is checked only when due for
 *    the caller's interval; checks are conditional (ETag / Last-Modified)
 *    and an unchanged body (same SHA-256) is not rewritten.
 *  - **Last known good copy**: new content is streamed to a temporary file,
 *    validated, and only then atomically renamed over the cache. Network
 *    errors, HTTP errors and invalid bodies leave the previous copy intact.
 *  - **Graceful failure**: failed checks back off for [FAILURE_BACKOFF_MS]
 *    instead of hammering the server; mirrors are tried in order.
 */
@Singleton
class BrowserResourceRepository internal constructor(
    private val context: Context,
    private val httpClientProvider: HttpClientProvider,
    /** Download URLs for a resource in priority order (primary, then mirrors); a seam for tests. */
    private val urlsFor: (RemoteResource) -> List<String>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        httpClientProvider: HttpClientProvider,
    ) : this(context, httpClientProvider, { resource -> resource.downloadUrls })

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val rootDir = File(context.filesDir, ROOT_DIR_NAME)
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        // A crash between tmp write and rename can leave `.tmp` halves behind.
        try {
            rootDir.walkTopDown().filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Tmp sweep failed: ${e.message}")
        }
    }

    fun fileFor(id: RemoteResource): File = File(rootDir, id.cacheFileName)

    fun hasCache(id: RemoteResource): Boolean = fileFor(id).let { it.isFile && prefs.contains(key(id, KEY_SHA256)) }

    /** Wall-clock time of the last successful check (200 or 304), or 0. */
    fun lastCheckedAt(id: RemoteResource): Long = prefs.getLong(key(id, KEY_CHECKED_AT), 0L)

    /** Wall-clock time of the last failed check, or 0 when the last check succeeded. */
    fun lastFailedAt(id: RemoteResource): Long = prefs.getLong(key(id, KEY_FAILED_AT), 0L)

    /**
     * Removes the cached copy and every piece of metadata of [id] (an
     * imported list the user removed), so a later re-import starts clean.
     */
    fun delete(id: RemoteResource) {
        synchronized(locks.getOrPut(id.key) { Any() }) {
            fileFor(id).delete()
            prefs.edit {
                listOf(KEY_ETAG, KEY_LAST_MODIFIED, KEY_CHECKED_AT, KEY_FAILED_AT, KEY_SHA256)
                    .forEach { remove(key(id, it)) }
            }
        }
    }

    /**
     * Whether [id] should be checked now. A missing cache is always due (so a
     * fresh install bootstraps even with automatic updates off); otherwise the
     * resource is due once [intervalMs] elapsed since the last successful
     * check. A null interval means "manual updates only". Recent failures
     * defer the next attempt by [FAILURE_BACKOFF_MS].
     */
    fun isDue(id: RemoteResource, intervalMs: Long?, now: Long = System.currentTimeMillis()): Boolean {
        val failedAt = prefs.getLong(key(id, KEY_FAILED_AT), 0L)
        if (failedAt in (now - FAILURE_BACKOFF_MS + 1)..now) return false
        if (!hasCache(id)) return true
        if (intervalMs == null) return false
        val checkedAt = lastCheckedAt(id)
        // A clock moved backwards must not postpone updates indefinitely.
        return checkedAt > now || now - checkedAt >= intervalMs
    }

    /**
     * Refreshes [id] when due (or always when [force]). [validate] inspects a
     * freshly downloaded file before it may replace the cached copy.
     */
    fun refresh(
        id: RemoteResource,
        intervalMs: Long?,
        force: Boolean,
        validate: (File) -> Boolean,
    ): BrowserResourceRefreshResult {
        synchronized(locks.getOrPut(id.key) { Any() }) {
            if (!force && !isDue(id, intervalMs)) {
                return result(id, checked = false, updated = false, failed = false)
            }
            for (url in urlsFor(id)) {
                val outcome = fetch(id, url, conditional = hasCache(id), validate = validate)
                if (outcome != null) return outcome
            }
            prefs.edit { putLong(key(id, KEY_FAILED_AT), System.currentTimeMillis()) }
            return result(id, checked = true, updated = false, failed = true)
        }
    }

    /** One HTTP attempt against [url]; null means "failed, try the next mirror". */
    private fun fetch(
        id: RemoteResource,
        url: String,
        conditional: Boolean,
        validate: (File) -> Boolean,
    ): BrowserResourceRefreshResult? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (conditional) {
                    prefs.getString(key(id, KEY_ETAG), null)?.let { header("If-None-Match", it) }
                    prefs.getString(key(id, KEY_LAST_MODIFIED), null)?.let { header("If-Modified-Since", it) }
                }
            }
            .get()
            .build()

        return try {
            httpClientProvider.client.newCall(request).execute().use { response ->
                when (response.code) {
                    HTTP_NOT_MODIFIED -> if (conditional) {
                        // A 304 may omit validators; the stored ones stay valid.
                        markChecked(id, response.header("ETag"), response.header("Last-Modified"), sha256 = null, fullResponse = false)
                        result(id, checked = true, updated = false, failed = false)
                    } else {
                        null
                    }
                    HTTP_OK -> {
                        val target = fileFor(id)
                        target.parentFile?.mkdirs()
                        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
                        try {
                            val digest = download(response.body.byteStream(), tmp)
                            if (!validate(tmp)) {
                                Log.w(TAG, "Resource ${id.key}: invalid content from $url; keeping cached copy")
                                return@use null
                            }
                            val unchanged = target.isFile && digest == prefs.getString(key(id, KEY_SHA256), null)
                            if (!unchanged) replace(tmp, target)
                            markChecked(id, response.header("ETag"), response.header("Last-Modified"), digest, fullResponse = true)
                            result(id, checked = true, updated = !unchanged, failed = false)
                        } finally {
                            tmp.delete()
                        }
                    }
                    else -> {
                        Log.w(TAG, "Resource ${id.key}: HTTP ${response.code} from $url")
                        null
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Resource ${id.key}: fetch from $url failed: ${e.message}")
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "Resource ${id.key}: unexpected fetch failure", e)
            null
        }
    }

    /** Streams [input] into [tmp], enforcing [MAX_RESOURCE_BYTES]; returns the SHA-256 hex digest. */
    private fun download(input: java.io.InputStream, tmp: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        tmp.outputStream().buffered().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_RESOURCE_BYTES) throw IOException("Resource exceeds $MAX_RESOURCE_BYTES bytes")
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /** Atomic replace: rename(2) swaps the file in one step, readers see old or new, never half. */
    private fun replace(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        tmp.copyTo(target, overwrite = true)
    }

    /**
     * Records a successful check. A full (200) response replaces the stored
     * validators — ones it lacks are dropped, since they may belong to another
     * mirror's copy — while a 304 only refreshes the ones it carries.
     */
    private fun markChecked(
        id: RemoteResource,
        etag: String?,
        lastModified: String?,
        sha256: String?,
        fullResponse: Boolean,
    ) {
        prefs.edit {
            putLong(key(id, KEY_CHECKED_AT), System.currentTimeMillis())
            remove(key(id, KEY_FAILED_AT))
            putValidator(key(id, KEY_ETAG), etag, fullResponse)
            putValidator(key(id, KEY_LAST_MODIFIED), lastModified, fullResponse)
            if (sha256 != null) putString(key(id, KEY_SHA256), sha256)
        }
    }

    private fun SharedPreferences.Editor.putValidator(key: String, value: String?, removeWhenAbsent: Boolean) {
        when {
            value != null -> putString(key, value)
            removeWhenAbsent -> remove(key)
        }
    }

    private fun result(id: RemoteResource, checked: Boolean, updated: Boolean, failed: Boolean) =
        BrowserResourceRefreshResult(id, checked, updated, failed, available = hasCache(id))

    private fun key(id: RemoteResource, suffix: String): String = "${id.key}_$suffix"

    private companion object {
        const val TAG = "BrowserResources"
        const val ROOT_DIR_NAME = "browser_resources"
        const val PREFS_NAME = "BrowserResourceMetadata"
        const val USER_AGENT = "Nexa"
        const val TMP_SUFFIX = ".tmp"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_MODIFIED = "last_modified"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_FAILED_AT = "failed_at"
        const val KEY_SHA256 = "sha256"
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
        const val FAILURE_BACKOFF_MS = 30 * 60 * 1000L
        const val MAX_RESOURCE_BYTES = 16L * 1024 * 1024
    }
}
