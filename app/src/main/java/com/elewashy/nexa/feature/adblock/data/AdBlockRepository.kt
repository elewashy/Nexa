package com.elewashy.nexa.feature.adblock.data

import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.engine.CachingRegistrableDomainResolver
import com.elewashy.nexa.feature.adblock.data.engine.FilterEngine
import com.elewashy.nexa.feature.adblock.data.engine.FilterEngineSnapshot
import com.elewashy.nexa.feature.adblock.data.lists.BuiltInFilterList
import com.elewashy.nexa.feature.adblock.data.lists.FilterListSource
import com.elewashy.nexa.feature.adblock.data.lists.ImportedFilterList
import com.elewashy.nexa.feature.adblock.data.resources.BrowserResourceRepository
import com.elewashy.nexa.feature.adblock.data.resources.RemoteResource
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatus
import com.elewashy.nexa.feature.adblock.domain.model.FilterListInfo
import com.elewashy.nexa.feature.adblock.domain.model.FilterListState
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the content-blocking [FilterEngine]: compiles the enabled filter
 * lists plus the user's custom rules, keeps the lists up to date and
 * publishes the active engine.
 *
 * Inputs (all persisted, observed reactively): the global switch, the
 * filter-list selection ([FilterListRepository]), the enabled custom rules
 * and their trust setting ([CustomRuleRepository]). Any change recompiles in
 * the background after a short debounce; browsing continues on the
 * previous engine meanwhile.
 *
 * Rule precedence is the engine's single, uBO-compatible order — custom
 * rules are compiled into the same engine as the lists, so `$important`,
 * `@@` exceptions and `$badfilter` apply across lists and custom rules
 * alike. Per-site switches are not rules: they are applied before the
 * engine is consulted ([AdBlockPolicyStore]).
 *
 * Lifecycle:
 *  - At construction the engine is restored from the snapshot saved after
 *    the last compile ([FilterEngineSnapshot]) when the inputs and the app
 *    build are unchanged — a fraction of the cost of compiling, like uBO's
 *    selfie — otherwise compiled (and a new snapshot saved), all on a
 *    dedicated low-priority thread. Until the engine is ready, WebView IO
 *    threads wait for it (bounded by [INITIAL_LOAD_TIMEOUT_MS]) so the first
 *    page of a session is filtered exactly like every other page.
 *  - [refresh] checks the lists (conditional requests, per-list intervals,
 *    failure backoff — see [BrowserResourceRepository]) and recompiles only
 *    when the cached lists actually changed.
 *  - The active engine is immutable and swapped with a single volatile
 *    write: in-flight requests finish on the old engine, new requests see the
 *    new one, and a failed update or compile leaves the last working engine
 *    in place.
 *  - With content blocking turned off the engine is released, so a disabled
 *    blocker costs no memory; turning it back on restores the snapshot.
 */
@Singleton
class AdBlockRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val resourceRepository: BrowserResourceRepository,
    private val filterListRepository: FilterListRepository,
    customRuleRepository: CustomRuleRepository,
    appPreferences: AppPreferences,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Everything a compiled engine depends on besides the cached list files. */
    private data class EngineInputs(
        val enabled: Boolean,
        val lists: List<FilterListSource>,
        val customRules: List<String>,
        val trustCustomRules: Boolean,
    )

    private val inputs: Flow<EngineInputs> = combine(
        appPreferences.adBlockEnabled,
        filterListRepository.enabledLists,
        customRuleRepository.enabledRuleTexts,
        customRuleRepository.trusted,
        ::EngineInputs,
    ).distinctUntilChanged()

    private val resolver = CachingRegistrableDomainResolver(PublicSuffixDomainResolver)

    @Volatile
    private var engine: FilterEngine = FilterEngine.empty(resolver)

    /** Signature of the inputs compiled into [engine]. */
    @Volatile
    private var compiledSignature: String? = null

    /** Rule counts per source of the active engine; drives the per-list rule counts. */
    private val sourceRuleCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Bumped whenever list cache metadata may have changed (update pass finished). */
    private val metadataVersion = MutableStateFlow(0)

    private val initialLoad = CountDownLatch(1)
    private val updateMutex = Mutex()

    @Volatile private var disabled = false
    @Volatile private var compiling = false
    @Volatile private var updating = false

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

    /** Every catalog and imported list with its selection, state and rule count. */
    val filterLists: Flow<List<FilterListInfo>> = combine(
        filterListRepository.lists,
        sourceRuleCounts,
        metadataVersion,
    ) { lists, counts, _ ->
        lists.map { selected ->
            val source = selected.source
            val hasCache = resourceRepository.hasCache(source)
            val failedAt = resourceRepository.lastFailedAt(source)
            FilterListInfo(
                key = source.key,
                title = source.title,
                category = source.category,
                enabled = selected.enabled,
                state = when {
                    !selected.enabled -> FilterListState.Disabled
                    hasCache && failedAt > 0L -> FilterListState.UpdateFailed
                    hasCache -> FilterListState.Active
                    failedAt > 0L -> FilterListState.Unavailable
                    else -> FilterListState.Downloading
                },
                ruleCount = counts[source.key]?.takeIf { selected.enabled && hasCache },
                lastUpdatedAt = resourceRepository.lastCheckedAt(source),
                lastFailedAt = failedAt,
                regions = (source as? BuiltInFilterList)?.regions.orEmpty(),
                url = (source as? ImportedFilterList)?.url,
                isDefault = selected.isDefault,
            )
        }
    }.flowOn(ioDispatcher)

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
                compileIfChanged(inputs.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Initial filter load failed", e)
            } finally {
                initialLoad.countDown()
                publishPhase()
            }
            observeInputs()
        }
    }

    /** Recompiles (debounced) whenever the selection, custom rules or switches change. */
    @OptIn(FlowPreview::class)
    private suspend fun observeInputs() {
        try {
            inputs.debounce(RECOMPILE_DEBOUNCE_MS).collect { compileIfChanged(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Filter inputs unavailable; keeping the current engine", e)
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
     * Checks the enabled lists and recompiles when anything changed. Does
     * nothing while content blocking is turned off.
     *
     * @param intervalMs automatic update interval; null = manual mode (only
     *   missing lists are fetched).
     * @param force check every list now regardless of interval or backoff.
     */
    suspend fun refresh(intervalMs: Long?, force: Boolean): FilterUpdateResult = updateMutex.withLock {
        // Startup cleanup/compile must finish before lists are rewritten.
        withContext(ioDispatcher) { initialLoad.await() }
        val current = inputs.first()
        if (!current.enabled) return@withLock FilterUpdateResult(checked = 0, updated = 0, failed = 0)
        val lists = current.lists
        val due = if (force) lists else lists.filter { resourceRepository.isDue(it, intervalMs) }
        if (due.isEmpty()) {
            withContext(engineDispatcher) { compileIfChanged(inputs.first()) }
            return@withLock FilterUpdateResult(checked = 0, updated = 0, failed = 0)
        }

        updating = true
        publishPhase()
        try {
            val results = withContext(ioDispatcher) {
                coroutineScope {
                    due.map { source ->
                        async {
                            resourceRepository.refresh(source, intervalMs, force) { file -> isValidFilterList(source, file) }
                        }
                    }.awaitAll()
                }
            }
            for (result in results) {
                val imported = result.resource as? ImportedFilterList ?: continue
                if (!result.updated) continue
                withContext(ioDispatcher) { readListTitle(resourceRepository.fileFor(imported)) }
                    ?.let { filterListRepository.setImportedTitle(imported.key, it) }
            }
            val outcome = FilterUpdateResult(
                checked = results.count { it.checked && !it.failed },
                updated = results.count { it.updated },
                failed = results.count { it.failed },
            )
            withContext(engineDispatcher) { compileIfChanged(inputs.first()) }
            if (outcome.updated > 0) showUpdatedNotification(outcome.updated)
            Log.i(TAG, "Filter update: $outcome")
            outcome
        } finally {
            updating = false
            _status.update { status -> status.copy(failedLists = lists.count { resourceRepository.lastFailedAt(it) > 0L }) }
            metadataVersion.update { it + 1 }
            publishPhase()
        }
    }

    /** Earliest time any enabled list becomes due for [intervalMs], or null in manual mode / when disabled. */
    suspend fun nextDueAt(intervalMs: Long?): Long? {
        if (intervalMs == null) return null
        val current = inputs.first()
        if (!current.enabled) return null
        return current.lists.minOfOrNull { resourceRepository.lastCheckedAt(it) + intervalMs }
    }

    /** Whether some enabled list has never been downloaded successfully. */
    suspend fun hasMissingLists(): Boolean {
        val current = inputs.first()
        return current.enabled && current.lists.any { !resourceRepository.hasCache(it) }
    }

    // ── Compilation ─────────────────────────────────────────────────────

    /**
     * Recompiles when the inputs differ from the compiled ones, preferring a
     * matching snapshot. Runs on [engineDispatcher].
     */
    private fun compileIfChanged(inputs: EngineInputs) {
        if (!inputs.enabled) {
            if (compiledSignature != DISABLED_SIGNATURE) {
                engine = FilterEngine.empty(resolver)
                compiledSignature = DISABLED_SIGNATURE
                sourceRuleCounts.value = emptyMap()
                _status.update { AdBlockStatus(phase = it.phase) }
            }
            disabled = true
            publishPhase()
            return
        }
        disabled = false
        val signature = signature(inputs)
        if (signature == compiledSignature) {
            publishPhase()
            return
        }
        compiling = true
        publishPhase()
        try {
            compile(inputs, signature)
        } finally {
            compiling = false
            publishPhase()
        }
    }

    private fun compile(inputs: EngineInputs, signature: String) {
        val snapshotKey = if (buildId.isEmpty()) null else "$buildId|$signature"
        if (snapshotKey != null) {
            restoreSnapshot(snapshotKey)?.let { restored ->
                publish(restored, signature, inputs)
                return
            }
        }
        val start = System.nanoTime()
        val compiled = try {
            val builder = FilterEngine.Builder(resolver)
            for (source in inputs.lists) {
                if (!resourceRepository.hasCache(source)) continue
                try {
                    val stats = resourceRepository.fileFor(source).bufferedReader().use {
                        builder.addList(it, source.trusted, sourceKey = source.key)
                    }
                    Log.d(TAG, "${source.key}: $stats")
                } catch (e: IOException) {
                    // An unreadable list is skipped; the others still compile.
                    Log.e(TAG, "Failed to read ${source.key}", e)
                }
            }
            if (inputs.customRules.isNotEmpty()) {
                val stats = builder.addRules(inputs.customRules, inputs.trustCustomRules, sourceKey = CUSTOM_RULES_KEY)
                Log.d(TAG, "custom rules: $stats")
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
        publish(compiled, signature, inputs)
        Log.i(TAG, "Compiled ${inputs.lists.size} lists in ${(System.nanoTime() - start) / 1_000_000} ms: ${compiled.stats}")
        if (snapshotKey != null) saveSnapshot(compiled, snapshotKey)
    }

    private fun publish(compiled: FilterEngine, signature: String, inputs: EngineInputs) {
        engine = compiled
        compiledSignature = signature
        sourceRuleCounts.value = compiled.sourceRuleCounts
        _status.update {
            it.copy(
                networkFilters = compiled.stats.networkFilters,
                cosmeticFilters = compiled.stats.cosmeticFilters,
                scriptletFilters = compiled.stats.scriptletFilters,
                activeLists = inputs.lists.count { list -> resourceRepository.hasCache(list) },
                enabledLists = inputs.lists.size,
                failedLists = inputs.lists.count { list -> resourceRepository.lastFailedAt(list) > 0L },
                customRules = compiled.sourceRuleCounts[CUSTOM_RULES_KEY] ?: 0,
            )
        }
    }

    private fun publishPhase() {
        val phase = when {
            disabled -> AdBlockStatus.Phase.Disabled
            compiling || initialLoad.count > 0L -> AdBlockStatus.Phase.Loading
            updating -> AdBlockStatus.Phase.Updating
            else -> AdBlockStatus.Phase.Ready
        }
        _status.update { it.copy(phase = phase) }
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

    /** Identity of the inputs: every list's cached file plus the custom rules and their trust. */
    private fun signature(inputs: EngineInputs): String = buildString {
        for (source in inputs.lists) {
            val file = resourceRepository.fileFor(source)
            append(source.key).append(':')
            if (resourceRepository.hasCache(source)) append(file.length()).append(':').append(file.lastModified()) else append('-')
            append('|')
        }
        append("custom:").append(sha256(inputs.customRules)).append(":trusted=").append(inputs.trustCustomRules)
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
        private const val RECOMPILE_DEBOUNCE_MS = 400L
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_TIMEOUT_MS = 3_000L
        private const val HTML_SNIFF_BYTES = 512
        private const val TITLE_SCAN_LINES = 40
        private const val SNAPSHOT_PATH = "adblock/engine.snapshot"
        private const val DISABLED_SIGNATURE = "disabled"

        /** Source key of the custom rules in [FilterEngine.sourceRuleCounts]. */
        const val CUSTOM_RULES_KEY = "custom-rules"

        /**
         * Rejects downloads that are obviously not filter lists (captive
         * portals and error pages return HTML with 200). Nexa's own list may be
         * legitimately empty; third-party lists never are.
         */
        internal fun isValidFilterList(id: RemoteResource, file: File): Boolean {
            if (!file.isFile) return false
            if (file.length() == 0L) return id == BuiltInFilterList.NexaFilters
            val head = file.inputStream().use { input ->
                val buffer = ByteArray(HTML_SNIFF_BYTES)
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
            val start = head.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (start.startsWith("<")) return false
            return true
        }

        /** The `! Title:` header of a filter list, if it declares one near the top. */
        internal fun readListTitle(file: File): String? = try {
            file.bufferedReader().useLines { lines ->
                lines.take(TITLE_SCAN_LINES)
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("! Title:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            null
        }

        private fun sha256(lines: List<String>): String {
            if (lines.isEmpty()) return "-"
            val digest = MessageDigest.getInstance("SHA-256")
            for (line in lines) {
                digest.update(line.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
