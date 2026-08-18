package com.elewashy.nexa.feature.downloads.data.persistence

import android.content.Context
import android.util.Log
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Persisted byte-range state of a single download segment.
 *
 * Segments complete out of order, so resume after process death must NOT
 * assume bytes [0..downloadedBytes] are contiguous — each segment's own
 * offset/length/progress is restored and verified instead.
 */
data class PersistedSegment(
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long,
    val completed: Boolean
)

/**
 * Handles persisting and restoring [DownloadItem] + segment state.
 *
 * Storage format: a single JSON document in `filesDir` written via
 * tmp-file + atomic rename, so a crash mid-write can never leave a torn
 * state file. Writes happen only when dirty (debounced by the caller's
 * periodic flush), reads happen lazily on the first [load] call.
 *
 * Key design decisions:
 *  - **Debounced writes**: [markDirty] sets a flag; the actual disk write
 *    happens only when [flushIfDirty] is called (typically every ~2 seconds
 *    or at service lifecycle boundaries).
 *  - **Thread-safe**: [writeToDisk] is `@Synchronized`; the dirty flag is an
 *    [AtomicBoolean] so [markDirty] is essentially zero-cost on the hot path.
 */
class DownloadPersistence private constructor(
    private val appContext: Context?,
    stateDir: File,
) {

    /** Production path: state lives in the app's filesDir. */
    constructor(context: Context) : this(context.applicationContext, context.applicationContext.filesDir)

    /**
     * Test seam: JVM unit tests construct against a plain directory.
     * Legacy SharedPreferences migration is skipped (there is no Context).
     */
    internal constructor(stateDir: File) : this(null, stateDir)

    companion object {
        private const val TAG = "DlPersistence"
        private const val STATE_FILE_NAME = "download_state.json"

        /** Corrupt state files are renamed with this suffix + timestamp, never deleted. */
        private const val CORRUPT_SUFFIX = ".corrupt-"

        /** Legacy SharedPreferences storage — read once for migration, then cleared. */
        private const val LEGACY_PREFS_NAME = "DownloadPrefs"
        private const val LEGACY_KEY_ITEMS = "download_items"
        private const val LEGACY_KEY_LAST_ID = "last_download_id"

        /** Maximum number of completed downloads to persist (prevents unbounded growth). */
        private const val MAX_PERSISTED_COMPLETED = 100

        /** Pre-allocated TypeToken — avoids creating an anonymous class per load() call. */
        private val ITEM_LIST_TYPE = object : TypeToken<List<DownloadItem>>() {}.type
    }

    private val stateFile = File(stateDir, STATE_FILE_NAME)
    private val gson = Gson()

    /** Monotonically increasing download-ID counter. */
    val idCounter = AtomicLong(0)

    /** Dirty flag – set by [markDirty], cleared by [flushIfDirty]. */
    private val dirty = AtomicBoolean(false)

    /**
     * Sequence number of the last snapshot written to disk. Snapshots are
     * captured sequentially on the main thread but their IO writes run on a
     * thread pool — lock acquisition order is nondeterministic, so an older
     * snapshot could otherwise land AFTER a newer one and resurrect removed
     * items (or regress terminal states).
     */
    private val lastWrittenSeq = AtomicLong(0)

    /** Segment snapshots keyed by download id, populated by [load]. */
    private var restoredSegmentState: Map<Long, List<PersistedSegment>> = emptyMap()

    /**
     * Last non-empty segment state written to (or read from) disk, per item.
     * Guards against a flush persisting an EMPTY list over existing resumable
     * state (e.g. a restored-but-not-yet-resumed task whose in-memory segments
     * are still empty) — that would permanently destroy resume.
     */
    private var lastPersistedSegments: Map<Long, List<PersistedSegment>> = emptyMap()

    /** Set when state was migrated from SharedPreferences; cleared once the flat file is written. */
    private var legacyPendingClear = false

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

    /** Whether a write is pending since the last flush. */
    val isDirty: Boolean
        get() = dirty.get()

    /**
     * Writes to disk **only if** [markDirty] has been called since the last flush.
     *
     * @param items    current snapshot of download items
     * @param segments per-task segment snapshots (id → segments) to persist alongside the items
     * @param seq      capture-order token from the caller; writes with a seq older
     *                 than the last written one are dropped (0 = unsequenced).
     */
    fun flushIfDirty(
        items: Collection<DownloadItem>,
        segments: Map<Long, List<PersistedSegment>> = emptyMap(),
        seq: Long = 0
    ) {
        writeToDisk(items, segments, seq, onlyIfDirty = true)
    }

    /**
     * Forces an immediate write regardless of the dirty flag.
     * Use this at service-shutdown boundaries. [seq] behaves as in [flushIfDirty].
     */
    fun forceFlush(
        items: Collection<DownloadItem>,
        segments: Map<Long, List<PersistedSegment>> = emptyMap(),
        seq: Long = 0
    ) {
        writeToDisk(items, segments, seq, onlyIfDirty = false)
    }

    /** Segment state restored by [load] for a given download, if any. */
    fun restoredSegments(downloadId: Long): List<PersistedSegment> =
        restoredSegmentState[downloadId] ?: emptyList()

    /**
     * Loads previously persisted download state.
     *
     * - Active downloads (DOWNLOADING / PENDING) at time of crash/kill are
     *   converted to PAUSED so they don't auto-start.
     *
     * @return The list of restored items (may be empty).
     */
    fun load(): List<DownloadItem> {
        val document = try {
            readDocument()
        } catch (e: Exception) {
            // Quarantine the corrupt file and start empty — one parse error must
            // never silently destroy the whole history, and never crash the app.
            Log.e(TAG, "Corrupt download state file — quarantining", e)
            quarantineCorruptFile()
            idCounter.set(System.currentTimeMillis() / 1000)
            return emptyList()
        }

        if (document == null) {
            idCounter.set(System.currentTimeMillis() / 1000)
            return emptyList()
        }

        return try {
            idCounter.set(document.lastId)
            var maxId = 0L

            // Convert active downloads to paused on restore. Each item is
            // processed independently — one malformed entry must not abort the
            // whole batch.
            val items = mutableListOf<DownloadItem>()
            document.items.orEmpty().forEach { item ->
                try {
                    if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PENDING) {
                        item.status = DownloadStatus.PAUSED
                    }
                    items.add(item)
                    if (item.id > maxId) maxId = item.id
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unparseable download item: ${e.message}")
                }
            }
            val skipped = (document.items?.size ?: 0) - items.size
            if (skipped > 0) {
                Log.w(TAG, "Dropped $skipped unparseable download item(s) during load")
            }

            if (idCounter.get() <= maxId) {
                idCounter.set(maxId)
            }

            restoredSegmentState = document.segments.orEmpty()
            lastPersistedSegments = restoredSegmentState

            Log.d(TAG, "Loaded ${items.size} download items " +
                    "(${restoredSegmentState.size} segment snapshots) from storage")
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

    /** Flat-file document schema. Gson maps fields reflectively. */
    private class SavedState {
        var lastId: Long = 0
        var items: List<DownloadItem>? = null
        var segments: Map<Long, List<PersistedSegment>>? = null
    }

    private fun readDocument(): SavedState? {
        if (stateFile.exists()) {
            val json = stateFile.readText()
            if (json.isBlank()) return null
            return gson.fromJson(json, SavedState::class.java)
        }
        return migrateLegacyPrefs()
    }

    /**
     * Renames the corrupt state file (kept for inspection, never deleted) so
     * the next run starts from a clean slate instead of re-reading garbage.
     */
    private fun quarantineCorruptFile() {
        try {
            if (!stateFile.exists()) return
            val quarantined = File(
                stateFile.parentFile,
                "$STATE_FILE_NAME$CORRUPT_SUFFIX${System.currentTimeMillis()}"
            )
            if (stateFile.renameTo(quarantined)) {
                Log.w(TAG, "Quarantined corrupt state file → ${quarantined.name}")
            } else {
                Log.e(TAG, "Failed to quarantine corrupt state file: ${stateFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error quarantining corrupt state file", e)
        }
    }

    /** One-time migration from the old SharedPreferences+full-XML storage. */
    private fun migrateLegacyPrefs(): SavedState? {
        // Null only in the JVM test seam — no Context means no legacy prefs.
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(LEGACY_KEY_ITEMS, null) ?: return null

        return try {
            val items: List<DownloadItem> = gson.fromJson(json, ITEM_LIST_TYPE)
            legacyPendingClear = true
            Log.d(TAG, "Migrating ${items.size} items from legacy SharedPreferences storage")
            SavedState().apply {
                lastId = prefs.getLong(LEGACY_KEY_LAST_ID, 0L)
                this.items = items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating legacy download state", e)
            null
        }
    }

    /** Only clears the legacy prefs after the flat file has been written successfully. */
    private fun clearLegacyIfNeeded() {
        if (!legacyPendingClear) return
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            legacyPendingClear = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear legacy prefs: ${e.message}")
        }
    }

    @Synchronized
    private fun writeToDisk(
        items: Collection<DownloadItem>,
        segments: Map<Long, List<PersistedSegment>>,
        seq: Long,
        onlyIfDirty: Boolean
    ) {
        // Ordering guard — see [lastWrittenSeq]. Checked before touching the
        // dirty flag: a dropped stale write must not consume a dirty mark set
        // by changes newer than the already-written snapshot.
        if (seq != 0L && seq <= lastWrittenSeq.get()) {
            Log.w(TAG, "Skipping stale snapshot write (seq=$seq, written=${lastWrittenSeq.get()})")
            return
        }
        if (onlyIfDirty) {
            if (!dirty.compareAndSet(true, false)) return
        } else {
            dirty.set(false)
        }
        try {
            // Prune completed downloads to prevent unbounded growth.
            // Keep all non-completed items + only the most recent completed ones.
            val pruned = pruneCompleted(items.toList())
            val prunedIds = pruned.mapTo(HashSet()) { it.id }

            // Guard: never persist an EMPTY (or smaller) segment list over an
            // existing non-empty persisted state for a still-resumable item —
            // that permanently destroys resume (the next death would delete a
            // valid .part). Fall back to the last known-good snapshot instead.
            val merged = HashMap<Long, List<PersistedSegment>>()
            segments.forEach { (id, segs) ->
                if (segs.isNotEmpty()) merged[id] = segs
            }
            pruned.forEach { item ->
                if (item.status != DownloadStatus.DOWNLOADING && item.status != DownloadStatus.PAUSED) return@forEach
                if (merged[item.id].isNullOrEmpty() && !lastPersistedSegments[item.id].isNullOrEmpty()) {
                    merged[item.id] = lastPersistedSegments.getValue(item.id)
                    Log.w(TAG, "Kept last-good segments for download ${item.id} " +
                            "(incoming snapshot was empty)")
                }
            }
            merged.keys.retainAll(prunedIds)

            val state = SavedState().apply {
                lastId = idCounter.get()
                this.items = pruned
                this.segments = merged
            }

            val tmp = File(stateFile.parentFile, "$STATE_FILE_NAME.tmp")
            tmp.writeText(gson.toJson(state))
            try {
                Files.move(
                    tmp.toPath(), stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            lastPersistedSegments = merged
            if (seq != 0L) lastWrittenSeq.set(seq)
            clearLegacyIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving download state", e)
            // Stay dirty so the next periodic flush retries
            dirty.set(true)
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
