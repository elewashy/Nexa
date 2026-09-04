package com.elewashy.nexa.feature.downloads.data.persistence

import android.util.Log
import androidx.room.withTransaction
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** One atomic persistence unit: item row + its segment rows. */
data class DownloadSnapshot(
    val entity: DownloadEntity,
    val segments: List<DownloadSegmentEntity>,
)

/**
 * Room-backed download persistence coordinator.
 *
 * Write model (validated against the engine's real frequencies):
 *  - High-frequency progress ticks only mark an id dirty (no DB I/O).
 *  - A single-writer batch loop (500 ms) drains the dirty set, asks the
 *    repository for thread-safe snapshots, and writes one transaction per id —
 *    latest state wins and progress ticks coalesce.
 *  - Structural events (create / status change / delete) bypass the batch
 *    and write immediately through the SAME serialized writer context, so
 *    ordering vs. in-flight batches is total and a delete can never be
 *    resurrected by a stale progress write.
 *
 * All DB access happens on the serialized writer context — never main.
 */
@Singleton
class DownloadStore @Inject constructor(
    private val db: NexaDatabase,
    @IoDispatcher io: CoroutineDispatcher,
) {

    private val dao = db.downloadsDao()

    /** Single writer serializes every DB write; replaces the old seq guard. */
    private val writerContext: CoroutineContext = io.limitedParallelism(1)
    private val writerScope = CoroutineScope(SupervisorJob() + writerContext)

    private val dirty = ConcurrentHashMap.newKeySet<Long>()
    private val pendingDeletes = ConcurrentHashMap.newKeySet<Long>()

    // Failure health is operation-specific: a successful write for B must not
    // clear a still-pending failure for A and falsely report recovery.
    private val failedUpserts = ConcurrentHashMap.newKeySet<Long>()
    private val failedDeletes = ConcurrentHashMap.newKeySet<Long>()
    private val failureGeneration = AtomicLong(0)

    private val _lastWriteFailure = MutableStateFlow<Throwable?>(null)
    /** Observable persistence health; cleared by the next successful write. */
    val lastWriteFailure: StateFlow<Throwable?> = _lastWriteFailure.asStateFlow()

    /**
     * Repository-provided thread-safe snapshot capture. Returns current
     * entity + segment rows for the requested ids (empty-segment guard for
     * restored-but-unresumed items applied by the provider).
     */
    @Volatile
    var snapshotProvider: (Collection<Long>) -> List<DownloadSnapshot> = { emptyList() }

    // ── Batched progress persistence ────────────────────────────────────

    fun markProgress(id: Long) {
        if (id !in pendingDeletes) dirty.add(id)
    }

    /** Guards against a second loop when repository init retries. */
    @Volatile
    private var batchLoopStarted = false

    /** Starts the coalescing loop; call once after state load completes. */
    @Synchronized
    fun startBatchLoop() {
        if (batchLoopStarted) return
        batchLoopStarted = true
        writerScope.launch {
            var retryDelayMs = BATCH_INTERVAL_MS
            while (isActive) {
                delay(retryDelayMs)
                val failuresBefore = failureGeneration.get()
                try {
                    drainDirty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordFailure("Batched progress drain failed", e)
                }
                retryDelayMs = nextDrainDelay(
                    retryDelayMs,
                    failureGeneration.get() != failuresBefore,
                )
            }
        }
    }

    private suspend fun drainDirty() {
        // Deletes are durable intents: retain them until Room confirms success.
        // This prevents a disk-full failure from resurrecting a removed row.
        pendingDeletes.toList().forEach { id -> runDelete(id, rethrow = false) }

        if (dirty.isEmpty()) return
        val ids = dirty.toSet().filterNot { it in pendingDeletes }
        // Remove before capture so a progress tick arriving during the write
        // remains dirty. Failed ids are explicitly re-added below.
        dirty.removeAll(ids.toSet())
        val snapshots = try {
            snapshotProvider(ids)
        } catch (e: CancellationException) {
            dirty.addAll(ids)
            throw e
        } catch (e: Exception) {
            dirty.addAll(ids)
            failedUpserts.addAll(ids)
            recordFailure("Progress snapshot capture failed for downloads $ids", e)
            return
        }
        val capturedIds = snapshots.mapTo(mutableSetOf()) { it.entity.id }
        val missingIds = ids.filterNot { it in capturedIds || it in pendingDeletes }
        if (missingIds.isNotEmpty()) {
            dirty.addAll(missingIds)
            failedUpserts.addAll(missingIds)
            recordFailure(
                "Snapshot provider omitted downloads $missingIds",
                IllegalStateException("Missing download persistence snapshots"),
            )
        }
        try {
            snapshots.forEach { persistSnapshot(it, failureMessage = "Progress persist failed") }
        } catch (e: CancellationException) {
            // Re-queue the whole drained unit. Rewriting an already successful
            // latest-state snapshot is safe; dropping the not-yet-attempted
            // ids on scope cancellation is not.
            dirty.addAll(ids.filterNot { it in pendingDeletes })
            throw e
        }
    }

    /**
     * Writes one snapshot on the writer context. Failures are recorded and the
     * id is re-marked dirty so the batch loop retries; cancellation propagates
     * after re-marking so the intent is never lost.
     */
    private suspend fun persistSnapshot(snapshot: DownloadSnapshot, failureMessage: String) {
        val id = snapshot.entity.id
        if (id in pendingDeletes) return
        try {
            dao.upsertWithSegments(snapshot.entity, snapshot.segments)
            failedUpserts.remove(id)
            recordSuccessIfRecovered()
        } catch (e: CancellationException) {
            dirty.add(id)
            throw e
        } catch (e: Exception) {
            dirty.add(id)
            failedUpserts.add(id)
            recordFailure("$failureMessage for download $id", e)
        }
    }

    /** Records the delete intent so no later upsert for [id] can resurrect the row. */
    private fun markDeleteIntent(id: Long) {
        dirty.remove(id)
        // Delete supersedes every earlier upsert intent for this id.
        failedUpserts.remove(id)
        pendingDeletes.add(id)
    }

    /**
     * Executes a pending delete on the writer context. [pendingDeletes] keeps
     * the intent until Room confirms success, so a failed attempt is retried by
     * the batch loop; with [rethrow] the caller also receives the failure.
     */
    private suspend fun runDelete(id: Long, rethrow: Boolean) {
        try {
            dao.delete(id)
            pendingDeletes.remove(id)
            failedDeletes.remove(id)
            recordSuccessIfRecovered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedDeletes.add(id)
            recordFailure("Delete persist failed for download $id", e)
            if (rethrow) throw e
        }
    }

    /** Test seam: run one coalescing drain synchronously. */
    internal suspend fun drainDirtyNow() = drainDirty()

    // ── Structural persistence (immediate, serialized) ──────────────────

    /** Immediate serialized write for status transitions. */
    suspend fun upsertNow(ids: Collection<Long>) {
        val writableIds = ids.filterNot { it in pendingDeletes }
        dirty.removeAll(writableIds.toSet())
        try {
            withContext(writerContext) {
                val snapshots = snapshotProvider(writableIds)
                snapshots.forEach { dao.upsertWithSegments(it.entity, it.segments) }
            }
            failedUpserts.removeAll(writableIds.toSet())
            recordSuccessIfRecovered()
        } catch (e: CancellationException) {
            dirty.addAll(writableIds)
            throw e
        } catch (e: Exception) {
            // Keep the intent retryable by the batch loop. The caller still
            // receives the exception and can surface operation-specific state.
            dirty.addAll(writableIds)
            failedUpserts.addAll(writableIds)
            recordFailure("Structural persist failed for downloads $writableIds", e)
            throw e
        }
    }

    /** Fire-and-forget variant for paths that must not suspend (detach-adjacent). */
    fun postUpsert(ids: Collection<Long>) {
        writerScope.launch {
            try {
                upsertNow(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // upsertNow logged and retained dirty ids for retry.
            }
        }
    }

    suspend fun delete(id: Long) {
        markDeleteIntent(id)
        withContext(writerContext) { runDelete(id, rethrow = true) }
    }

    /** Fire-and-forget delete; [pendingDeletes] retains the intent for the batch loop on failure. */
    fun postDelete(id: Long) {
        markDeleteIntent(id)
        writerScope.launch { runDelete(id, rethrow = false) }
    }

    /**
     * Writes pre-captured snapshots through the serialized writer without
     * blocking the caller (service destruction). Capture happens on the main
     * thread BEFORE this is called; the write may finish after onDestroy
     * returns — the application scope outlives the service, and structural
     * state is already persisted immediately at each transition, so this only
     * needs to land the latest batched progress.
     */
    fun postSnapshots(snapshots: List<DownloadSnapshot>) {
        dirty.removeAll(snapshots.mapTo(mutableSetOf()) { it.entity.id })
        if (snapshots.isEmpty()) return
        writerScope.launch {
            snapshots.forEach { persistSnapshot(it, failureMessage = "Deferred snapshot persist failed") }
        }
    }

    // ── Queries ─────────────────────────────────────────────────────────

    suspend fun loadAll(): List<DownloadEntity> = withContext(writerContext) { dao.all() }

    suspend fun segmentsFor(id: Long): List<PersistedSegment> =
        withContext(writerContext) { dao.segmentsFor(id) }.map { it.toPersisted() }

    suspend fun count(): Int = withContext(writerContext) { dao.count() }

    suspend fun lastId(): Long? = withContext(writerContext) { dao.lastId() }

    suspend fun setLastId(value: Long) {
        withContext(writerContext) { dao.updateLastId(value) }
    }

    /** Whether the one-time legacy import already ran (authoritative gate). */
    suspend fun legacyImported(): Boolean =
        withContext(writerContext) { dao.legacyImported() ?: false }

    suspend fun markLegacyImported() {
        withContext(writerContext) { dao.markLegacyImported() }
    }

    /**
     * Prunes old completed rows and returns the deleted ids, so the caller
     * can mirror the prune in memory (the in-memory list must not outgrow
     * the database between restarts).
     */
    suspend fun pruneCompleted(keep: Int = MAX_PERSISTED_COMPLETED): List<Long> =
        withContext(writerContext) {
            db.withTransaction {
                val prunedIds = dao.completedIdsBeyondKeep(keep)
                if (prunedIds.isNotEmpty()) dao.deleteByIds(prunedIds)
                prunedIds
            }
        }

    suspend fun upsertEntity(entity: DownloadEntity) {
        withContext(writerContext) { dao.upsert(entity) }
    }

    /**
     * Transactional legacy import; rolls back wholly on unexpected failure.
     * One malformed record never aborts the batch — it is skipped, matching
     * the old backend's policy (and guarding against Gson leaving Kotlin
     * non-null fields null on legacy documents). Returns the imported count.
     */
    suspend fun importLegacy(state: LegacyDownloadState): Int =
        withContext(writerContext) {
            db.withTransaction {
                var imported = 0
                state.items.forEach { item ->
                    try {
                        // Gson may leave `status` null on malformed entries.
                        val rawStatus: DownloadStatus? = item.status
                        val coerced = when (rawStatus) {
                            DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> DownloadStatus.PAUSED
                            null -> DownloadStatus.PAUSED
                            else -> rawStatus
                        }
                        dao.upsert(item.toEntity(status = coerced))
                        state.segments[item.id]
                            ?.mapNotNull { it.toSegmentEntityOrNull(item.id) }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { dao.replaceSegments(item.id, it) }
                        imported++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unimportable legacy download ${item.id}: ${e.message}")
                    }
                }
                dao.setMeta(
                    DownloadMetaEntity(lastId = state.lastId, legacyImported = true)
                )
                imported
            }
        }

    private fun recordFailure(message: String, error: Exception) {
        failureGeneration.incrementAndGet()
        _lastWriteFailure.value = error
        Log.e(TAG, message, error)
    }

    private fun recordSuccessIfRecovered() {
        if (failedUpserts.isEmpty() && failedDeletes.isEmpty() && _lastWriteFailure.value != null) {
            Log.i(TAG, "Download persistence recovered")
            _lastWriteFailure.value = null
        }
    }

    companion object {
        private const val TAG = "DownloadStore"
        private const val BATCH_INTERVAL_MS = 500L
        private const val MAX_RETRY_INTERVAL_MS = 30_000L
        const val MAX_PERSISTED_COMPLETED = 100

        internal fun nextDrainDelay(currentMs: Long, failed: Boolean): Long =
            if (failed) (currentMs * 2).coerceAtMost(MAX_RETRY_INTERVAL_MS)
            else BATCH_INTERVAL_MS
    }
}

// ── Entity ↔ domain mapping ─────────────────────────────────────────────

fun DownloadItem.toEntity(status: DownloadStatus = this.status): DownloadEntity {
    // Gson-based legacy documents bypass constructors. Reference-typed fields
    // (String, enum) can be null where Kotlin expects values — coerce them.
    // Primitive fields are zero-filled by Unsafe allocation, never null.
    val safeSource: String? = source
    val safeFileName: String? = fileName
    val safeFilePath: String? = filePath
    val safeUrl: String? = url
    return DownloadEntity(
        id = id,
        url = safeUrl.orEmpty(),
        fileName = safeFileName.orEmpty(),
        filePath = safeFilePath.orEmpty(),
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        status = DownloadStatusCodes.toCode(status),
        mimeType = mimeType,
        userAgent = userAgent,
        referer = referer,
        origin = origin,
        cookies = cookies,
        source = safeSource ?: "UNKNOWN",
        createdAt = createdAt,
        wasWaitingForNetwork = wasWaitingForNetwork,
        errorMessage = errorMessage,
    )
}

fun DownloadEntity.toItem(): DownloadItem = DownloadItem(
    id = id,
    url = url,
    fileName = fileName,
    filePath = filePath,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    status = DownloadStatusCodes.fromCode(status),
    mimeType = mimeType,
    userAgent = userAgent,
    referer = referer,
    origin = origin,
    cookies = cookies,
    source = source,
    createdAt = createdAt,
    wasWaitingForNetwork = wasWaitingForNetwork,
    errorMessage = errorMessage,
)

/**
 * Maps a persisted segment to its entity, or null when the range is unusable.
 * Gson fills MISSING legacy fields with zero (Unsafe allocation), so a
 * document lacking fields yields degenerate ranges rather than an error;
 * dropping them degrades to a clean re-download on resume — never corrupts
 * resume state. Live engine snapshots always map successfully.
 */
fun PersistedSegment.toSegmentEntityOrNull(downloadId: Long): DownloadSegmentEntity? {
    if (startByte < 0 || endByte < startByte) return null
    // All-zero is the zero-fill artifact signature of a stripped document.
    if (startByte == 0L && endByte == 0L && downloadedBytes == 0L && !completed) return null
    return DownloadSegmentEntity(
        downloadId = downloadId,
        startByte = startByte,
        endByte = endByte,
        downloadedBytes = downloadedBytes.coerceIn(0L, endByte - startByte + 1),
        completed = completed,
    )
}

fun DownloadSegmentEntity.toPersisted(): PersistedSegment =
    PersistedSegment(
        startByte = startByte,
        endByte = endByte,
        downloadedBytes = downloadedBytes,
        completed = completed,
    )
