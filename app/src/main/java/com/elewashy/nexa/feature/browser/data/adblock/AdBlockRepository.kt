package com.elewashy.nexa.feature.browser.data.adblock

import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.os.LocaleListCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.core.storage.FilterTimestampStore
import com.elewashy.nexa.feature.browser.data.adblock.engine.CachingRegistrableDomainResolver
import com.elewashy.nexa.feature.browser.data.adblock.engine.FilterEngine
import com.elewashy.nexa.feature.browser.data.adblock.engine.FilterEngineSnapshot
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceId
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the content-blocking [FilterEngine]: compiles the cached filter lists,
 * keeps them up to date and publishes the active engine.
 *
 * Lifecycle:
 *  - At construction the engine is restored from the snapshot saved after
 *    the last compile ([FilterEngineSnapshot]) when the cached lists and the
 *    app build are unchanged — a fraction of the cost of compiling, like
 *    uBO's selfie — otherwise the lists are compiled (and a new snapshot
 *    saved), all on a dedicated low-priority thread. Until the engine is
 *    ready, WebView IO threads wait for it (bounded by
 *    [INITIAL_LOAD_TIMEOUT_MS]) — like uBO suspending network requests at
 *    browser launch — so the first page of a session is filtered exactly
 *    like every other page.
 *  - [refresh] checks the lists (conditional requests, per-list intervals,
 *    failure backoff — see [BrowserResourceRepository]) and recompiles only
 *    when the set of cached lists actually changed.
 *  - The active engine is immutable and swapped with a single volatile
 *    write: in-flight requests finish on the old engine, new requests see the
 *    new one, and a failed update or compile leaves the last working engine
 *    in place.
 */
@Singleton
class AdBlockRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val resourceRepository: BrowserResourceRepository,
    private val filterTimestampStore: FilterTimestampStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val resolver = CachingRegistrableDomainResolver(PublicSuffixDomainResolver)

    @Volatile
    private var engine: FilterEngine = FilterEngine.empty(resolver)

    /** Signature (name/size/mtime per list) of the lists compiled into [engine]. */
    @Volatile
    private var compiledSignature: String? = null

    private val initialLoad = CountDownLatch(1)
    private val updateMutex = Mutex()

    private val snapshotFile = File(context.noBackupFilesDir, SNAPSHOT_PATH)

    /** Changes with every install/update, so a new build never restores an engine compiled by an older one. */
    private val buildId: String by lazy {
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.lastUpdateTime}:${info.versionName}"
        } catch (e: Exception) {
            Log.w(TAG, "Package info unavailable; engine snapshots disabled", e)
            ""
        }
    }

    /** Compiles run here: one at a time, below UI/IO priority. */
    private val engineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "AdBlock-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _status = MutableStateFlow(AdBlockStatus())
    val status: StateFlow<AdBlockStatus> = _status.asStateFlow()

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        NotificationChannels.ensure(
            notificationManager = notificationManager,
            id = NotificationChannels.ADBLOCK,
            name = context.getString(R.string.adblock_channel_name),
            importance = NotificationChannels.IMPORTANCE_LOW,
            description = context.getString(R.string.adblock_channel_description),
            showBadge = false,
        )
        appScope.launch(engineDispatcher) {
            try {
                LegacyAdBlockData.delete(context)
                compileIfChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Initial filter load failed", e)
            } finally {
                initialLoad.countDown()
                _status.update { it.copy(phase = AdBlockStatus.Phase.Ready) }
            }
        }
    }

    /** The active engine, without waiting. Safe from any thread. */
    fun currentEngine(): FilterEngine = engine

    /**
     * The active engine, waiting (bounded) for the startup compile. Call only
     * from background threads such as WebView's `shouldInterceptRequest`.
     */
    fun awaitEngine(): FilterEngine {
        if (initialLoad.count > 0L) {
            try {
                initialLoad.await(INITIAL_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return engine
    }

    /**
     * Checks the enabled lists and recompiles when anything changed.
     *
     * @param intervalMs automatic update interval; null = manual mode (only
     *   missing lists are fetched).
     * @param force check every list now regardless of interval or backoff.
     */
    suspend fun refresh(intervalMs: Long?, force: Boolean): FilterUpdateResult = updateMutex.withLock {
        // Startup cleanup/compile must finish before lists are rewritten.
        withContext(ioDispatcher) { initialLoad.await() }
        val lists = enabledLists()
        val due = if (force) lists else lists.filter { resourceRepository.isDue(it, intervalMs) }
        if (due.isEmpty()) {
            if (signature(lists) != compiledSignature) withContext(engineDispatcher) { compileIfChanged() }
            return@withLock FilterUpdateResult(checked = 0, updated = 0, failed = 0)
        }

        _status.update { it.copy(phase = AdBlockStatus.Phase.Updating) }
        try {
            val results = withContext(ioDispatcher) {
                coroutineScope {
                    due.map { id ->
                        async {
                            resourceRepository.refresh(id, intervalMs, force) { file -> isValidFilterList(id, file) }
                        }
                    }.awaitAll()
                }
            }
            val outcome = FilterUpdateResult(
                checked = results.count { it.checked && !it.failed },
                updated = results.count { it.updated },
                failed = results.count { it.failed },
            )
            withContext(engineDispatcher) { compileIfChanged() }
            _status.update { it.copy(failedLists = outcome.failed) }
            if (outcome.success) filterTimestampStore.save()
            if (outcome.updated > 0) showUpdatedNotification(outcome.updated)
            Log.i(TAG, "Filter update: $outcome")
            outcome
        } finally {
            _status.update { it.copy(phase = AdBlockStatus.Phase.Ready) }
        }
    }

    /** Earliest time any enabled list becomes due for [intervalMs], or null in manual mode. */
    fun nextDueAt(intervalMs: Long?): Long? {
        if (intervalMs == null) return null
        return enabledLists().minOfOrNull { resourceRepository.lastCheckedAt(it) + intervalMs }
    }

    /** Whether some enabled list has never been downloaded successfully. */
    fun hasMissingLists(): Boolean = enabledLists().any { !resourceRepository.hasCache(it) }

    // ── Compilation ─────────────────────────────────────────────────────

    /**
     * Recompiles when the cached list set differs from the compiled one,
     * preferring a matching snapshot. Runs on [engineDispatcher].
     */
    private fun compileIfChanged() {
        val lists = enabledLists()
        val signature = signature(lists)
        if (signature == compiledSignature) return
        val snapshotKey = if (buildId.isEmpty()) null else "$buildId|$signature"
        if (snapshotKey != null) {
            restoreSnapshot(snapshotKey)?.let { restored ->
                publish(restored, signature, lists, lists.count { resourceRepository.hasCache(it) })
                return
            }
        }
        val start = System.nanoTime()
        var compiledLists = 0
        val compiled = try {
            val builder = FilterEngine.Builder(resolver)
            for (id in lists) {
                if (!resourceRepository.hasCache(id)) continue
                try {
                    val stats = resourceRepository.fileFor(id).bufferedReader().use { builder.addList(it, id.trusted) }
                    compiledLists++
                    Log.d(TAG, "${id.name}: $stats")
                } catch (e: java.io.IOException) {
                    // An unreadable list is skipped; the others still compile.
                    Log.e(TAG, "Failed to read ${id.name}", e)
                }
            }
            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Filter compilation failed; keeping the previous engine", e)
            return
        } catch (e: OutOfMemoryError) {
            // The previous engine is still referenced and keeps filtering.
            Log.e(TAG, "Out of memory compiling filters; keeping the previous engine", e)
            return
        }
        publish(compiled, signature, lists, compiledLists)
        Log.i(TAG, "Compiled $compiledLists lists in ${(System.nanoTime() - start) / 1_000_000} ms: ${compiled.stats}")
        if (snapshotKey != null) saveSnapshot(compiled, snapshotKey)
    }

    private fun publish(compiled: FilterEngine, signature: String, lists: List<BrowserResourceId>, activeLists: Int) {
        engine = compiled
        compiledSignature = signature
        _status.update {
            it.copy(
                networkFilters = compiled.stats.networkFilters,
                cosmeticFilters = compiled.stats.cosmeticFilters,
                scriptletFilters = compiled.stats.scriptletFilters,
                activeLists = activeLists,
                enabledLists = lists.size,
            )
        }
    }

    // ── Snapshot ────────────────────────────────────────────────────────

    /** The engine saved for [key], or null (missing, stale or unreadable snapshot). */
    private fun restoreSnapshot(key: String): FilterEngine? {
        if (!snapshotFile.isFile) return null
        val start = System.nanoTime()
        return try {
            val restored = snapshotFile.inputStream().use { FilterEngineSnapshot.read(it, key, resolver) }
            if (restored == null) {
                Log.i(TAG, "Engine snapshot is stale; recompiling")
            } else {
                Log.i(TAG, "Restored engine snapshot in ${(System.nanoTime() - start) / 1_000_000} ms: ${restored.stats}")
            }
            restored
        } catch (e: IOException) {
            Log.w(TAG, "Unreadable engine snapshot; recompiling", e)
            snapshotFile.delete()
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Invalid engine snapshot; recompiling", e)
            snapshotFile.delete()
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory restoring engine snapshot; recompiling", e)
            null
        }
    }

    /** Writes the snapshot atomically (temp file + rename); a failure only costs the next start a compile. */
    private fun saveSnapshot(compiled: FilterEngine, key: String) {
        val start = System.nanoTime()
        val dir = snapshotFile.parentFile ?: return
        val temp = File(dir, snapshotFile.name + ".tmp")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            temp.outputStream().use { FilterEngineSnapshot.write(compiled, key, it) }
            if (!temp.renameTo(snapshotFile)) throw IOException("Cannot replace $snapshotFile")
            Log.i(TAG, "Saved engine snapshot (${snapshotFile.length() / 1024} KB) in ${(System.nanoTime() - start) / 1_000_000} ms")
        } catch (e: IOException) {
            Log.w(TAG, "Engine snapshot not saved", e)
            temp.delete()
        }
    }

    private fun signature(lists: List<BrowserResourceId>): String = lists.joinToString("|") { id ->
        val file = resourceRepository.fileFor(id)
        if (resourceRepository.hasCache(id)) "${id.name}:${file.length()}:${file.lastModified()}" else "${id.name}:-"
    }

    private fun enabledLists(): List<BrowserResourceId> = BrowserResourceId.enabledFor(uiLanguage())

    private fun uiLanguage(): String {
        val appLocales: LocaleListCompat = AppCompatDelegate.getApplicationLocales()
        val locale = if (!appLocales.isEmpty) appLocales[0] else null
        return (locale ?: Locale.getDefault()).language
    }

    // ── Notifications ───────────────────────────────────────────────────

    private fun showUpdatedNotification(updatedLists: Int) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.ADBLOCK)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.adblock_update))
            .setContentText(context.resources.getQuantityString(R.plurals.updated_lists_count, updatedLists, updatedLists))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the update itself already succeeded.
            Log.d(TAG, "Update notification not shown: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AdBlockRepository"
        private const val INITIAL_LOAD_TIMEOUT_MS = 5_000L
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_TIMEOUT_MS = 3_000L
        private const val HTML_SNIFF_BYTES = 512
        private const val SNAPSHOT_PATH = "adblock/engine.snapshot"

        /**
         * Rejects downloads that are obviously not filter lists (captive
         * portals and error pages return HTML with 200). Nexa's own list may be
         * legitimately empty; third-party lists never are.
         */
        internal fun isValidFilterList(id: BrowserResourceId, file: File): Boolean {
            if (!file.isFile) return false
            if (file.length() == 0L) return id == BrowserResourceId.NexaFilters
            val head = file.inputStream().use { input ->
                val buffer = ByteArray(HTML_SNIFF_BYTES)
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
            val start = head.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (start.startsWith("<")) return false
            return true
        }
    }
}
