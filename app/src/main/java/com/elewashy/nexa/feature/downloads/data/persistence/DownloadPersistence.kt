package com.elewashy.nexa.feature.downloads.data.persistence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles persisting and restoring [DownloadItem] state to/from [SharedPreferences].
 *
 * Key design decisions:
 *  - **Debounced writes**: [save] sets a dirty flag; the actual disk write happens
 *    only when [flushIfDirty] is called (typically every ~2 seconds via a scheduled
 *    task or at service lifecycle boundaries). This avoids hammering the disk on
 *    every progress update.
 *  - **Thread-safe**: Guarded by `@Synchronized` on internal methods. The dirty
 *    flag is an [AtomicBoolean] so [save] is essentially zero-cost when called from
 *    the Fetch progress callback.
 */
class DownloadPersistence(context: Context) {

    companion object {
        private const val TAG = "DlPersistence"
        private const val PREFS_NAME = "DownloadPrefs"
        private const val KEY_ITEMS = "download_items"
        private const val KEY_LAST_ID = "last_download_id"

        /** Maximum number of completed downloads to persist (prevents unbounded growth). */
        private const val MAX_PERSISTED_COMPLETED = 100

        /** Pre-allocated TypeToken — avoids creating an anonymous class per load() call. */
        private val ITEM_LIST_TYPE = object : TypeToken<List<DownloadItem>>() {}.type
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Monotonically increasing download-ID counter. */
    val idCounter = AtomicLong(0)

    /** Dirty flag – set by [save], cleared by [flushIfDirty]. */
    private val dirty = AtomicBoolean(false)

    // ===================================================================
    //  Public API
    // ===================================================================

    /**
     * Marks data as changed. The actual write will happen on the next [flushIfDirty] call.
     * This is extremely cheap to call from the download-progress hot path.
     */
    fun markDirty() {
        dirty.set(true)
    }

    /**
     * Writes to disk **only if** [markDirty] has been called since the last flush.
     *
     * @param items current snapshot of download items
     */
    fun flushIfDirty(items: Collection<DownloadItem>) {
        if (!dirty.compareAndSet(true, false)) return
        writeToDisk(items)
    }

    /**
     * Forces an immediate write regardless of the dirty flag.
     * Use this at service-shutdown boundaries.
     */
    fun forceFlush(items: Collection<DownloadItem>) {
        dirty.set(false)
        writeToDisk(items)
    }

    /**
     * Loads previously persisted download state.
     *
     * - Active downloads (DOWNLOADING / PENDING) at time of crash/kill are
     *   converted to PAUSED so they don't auto-start.
     *
     * @return The list of restored items (may be empty).
     */
    fun load(): List<DownloadItem> {
        return try {
            val json = prefs.getString(KEY_ITEMS, null)
            val savedId = prefs.getLong(KEY_LAST_ID, 0L)
            idCounter.set(savedId)

            if (json == null) {
                idCounter.set(System.currentTimeMillis() / 1000)
                return emptyList()
            }

            val items: List<DownloadItem> = gson.fromJson(json, ITEM_LIST_TYPE)
            var maxId = 0L

            items.forEach { item ->
                // Convert active downloads to paused on restore
                if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PENDING) {
                    item.status = DownloadStatus.PAUSED
                }
                if (item.id > maxId) maxId = item.id
            }

            if (idCounter.get() <= maxId) {
                idCounter.set(maxId)
            }

            Log.d(TAG, "Loaded ${items.size} download items from storage")
            items
        } catch (e: Exception) {
            Log.e(TAG, "Error loading download state", e)
            idCounter.set(System.currentTimeMillis() / 1000)
            emptyList()
        }
    }

    // ===================================================================
    //  Internal
    // ===================================================================

    @Synchronized
    private fun writeToDisk(items: Collection<DownloadItem>) {
        try {
            // Prune completed downloads to prevent unbounded SharedPreferences growth.
            // Keep all non-completed items + only the most recent completed ones.
            val pruned = pruneCompleted(items.toList())

            val json = gson.toJson(pruned)
            prefs.edit {
                putString(KEY_ITEMS, json)
                putLong(KEY_LAST_ID, idCounter.get())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving download state", e)
        }
    }

    /**
     * Prunes old completed downloads, keeping at most [MAX_PERSISTED_COMPLETED].
     * Non-completed items (active, paused, failed) are always kept.
     */
    private fun pruneCompleted(items: List<DownloadItem>): List<DownloadItem> {
        val (completed, active) = items.partition { it.status == DownloadStatus.COMPLETED }

        if (completed.size <= MAX_PERSISTED_COMPLETED) return items

        // Keep the most recent completed downloads
        val keptCompleted = completed
            .sortedByDescending { it.createdAt }
            .take(MAX_PERSISTED_COMPLETED)

        val pruned = completed.size - keptCompleted.size
        if (pruned > 0) {
            Log.d(TAG, "Pruned $pruned old completed downloads from persistence")
        }

        return active + keptCompleted
    }
}
