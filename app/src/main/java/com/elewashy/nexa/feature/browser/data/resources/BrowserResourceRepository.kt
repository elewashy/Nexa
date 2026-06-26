package com.elewashy.nexa.feature.browser.data.resources

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.elewashy.nexa.core.network.HttpClientProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserResourceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClientProvider: HttpClientProvider,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val rootDir = File(context.filesDir, ROOT_DIR_NAME)
    private val locks = BrowserResourceId.entries.associateWith { Any() }

    fun fileFor(id: BrowserResourceId): File = File(rootDir, id.cacheFileName)

    fun readText(id: BrowserResourceId): String? {
        return readCachedText(id)
    }

    fun refreshAll(force: Boolean = false): List<BrowserResourceRefreshResult> =
        BrowserResourceId.entries.map { refresh(it, force) }

    fun refresh(resources: List<BrowserResourceId>, force: Boolean = false): List<BrowserResourceRefreshResult> =
        resources.map { refresh(it, force) }

    fun refresh(id: BrowserResourceId, force: Boolean = false): BrowserResourceRefreshResult {
        synchronized(locks.getValue(id)) {
            if (!force && !isDue(id)) {
                return BrowserResourceRefreshResult(id, checked = false, updated = false, available = fileFor(id).exists())
            }

            val request = Request.Builder()
                .url(id.remoteUrl)
                .header("User-Agent", "Nexa")
                .apply {
                    prefs.getString(key(id, KEY_ETAG), null)?.let { header("If-None-Match", it) }
                    prefs.getString(key(id, KEY_LAST_MODIFIED), null)?.let { header("If-Modified-Since", it) }
                }
                .get()
                .build()

            return try {
                httpClientProvider.client.newCall(request).execute().use { response ->
                    when (response.code) {
                        HTTP_NOT_MODIFIED -> {
                            saveCheckedAt(id)
                            BrowserResourceRefreshResult(id, checked = true, updated = false, available = fileFor(id).exists())
                        }
                        HTTP_OK -> {
                            val body = response.body.string()
                            if (body.isBlank()) {
                                saveCheckedAt(id)
                                BrowserResourceRefreshResult(id, checked = true, updated = false, available = fileFor(id).exists())
                            } else {
                                val updated = writeIfChanged(id, body)
                                saveMetadata(id, response.header("ETag"), response.header("Last-Modified"))
                                BrowserResourceRefreshResult(id, checked = true, updated = updated, available = true)
                            }
                        }
                        else -> {
                            Log.w(TAG, "Resource ${id.name} check failed: HTTP ${response.code}")
                            BrowserResourceRefreshResult(id, checked = true, updated = false, available = fileFor(id).exists())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resource ${id.name} refresh failed", e)
                BrowserResourceRefreshResult(id, checked = true, updated = false, available = fileFor(id).exists())
            }
        }
    }

    private fun readCachedText(id: BrowserResourceId): String? {
        val file = fileFor(id)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.readText().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt cache for ${id.name}; deleting", e)
            file.delete()
            null
        }
    }

    private fun writeIfChanged(id: BrowserResourceId, text: String): Boolean {
        val file = fileFor(id)
        val current = readCachedText(id)
        if (current == text) {
            saveCheckedAt(id)
            return false
        }

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        saveCheckedAt(id)
        return true
    }

    private fun isDue(id: BrowserResourceId): Boolean {
        if (id.owner == BrowserResourceOwner.Nexa) return true
        val lastCheckedAt = prefs.getLong(key(id, KEY_CHECKED_AT), 0L)
        return System.currentTimeMillis() - lastCheckedAt >= id.updateIntervalMs
    }

    private fun saveMetadata(id: BrowserResourceId, etag: String?, lastModified: String?) {
        prefs.edit {
            putLong(key(id, KEY_CHECKED_AT), System.currentTimeMillis())
            if (etag != null) putString(key(id, KEY_ETAG), etag)
            if (lastModified != null) putString(key(id, KEY_LAST_MODIFIED), lastModified)
        }
    }

    private fun saveCheckedAt(id: BrowserResourceId) {
        prefs.edit { putLong(key(id, KEY_CHECKED_AT), System.currentTimeMillis()) }
    }

    private fun key(id: BrowserResourceId, suffix: String): String = "${id.name}_$suffix"

    private companion object {
        const val TAG = "BrowserResources"
        const val ROOT_DIR_NAME = "browser_resources"
        const val PREFS_NAME = "BrowserResourceMetadata"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_MODIFIED = "last_modified"
        const val KEY_CHECKED_AT = "checked_at"
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
    }
}
