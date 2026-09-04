package com.elewashy.nexa.feature.tabs.data

import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.BrowserUrls
import com.elewashy.nexa.core.common.DispatcherProvider
import com.elewashy.nexa.core.util.SafeUrls.isSafeLoadableUrl
import com.elewashy.nexa.feature.tabs.data.persistence.TabEntity
import com.elewashy.nexa.feature.tabs.data.persistence.TabsDao
import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Workspace (tab list + active pointer) persistence coordinator.
 *
 * Write policy — the Phase 2 download-store lesson applied to tabs:
 *  - Structural events (create / close / switch / restore healing) commit
 *    immediately, one transaction each, so any kill leaves a valid workspace.
 *  - URL commits and title updates are high-frequency and non-structural:
 *    they are coalesced for [COALESCE_WINDOW_MS] and written as plain
 *    UPDATEs. Losing up to one window of them on a kill is harmless —
 *    restore degrades to the last persisted URL/title.
 *
 * All mutations are serialized on [mutex]. [workspace] is the UI's single source of truth and
 * publishes the ordered tabs, active pointer, and restore status atomically while Room catches up.
 */
@Singleton
class TabRepositoryImpl @Inject constructor(
    private val dao: TabsDao,
    @ApplicationScope private val appScope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : TabRepository {

    private val _workspace = MutableStateFlow(TabWorkspaceState())
    override val workspace: StateFlow<TabWorkspaceState> = _workspace.asStateFlow()

    private val mutex = Mutex()

    /** Private tabs deliberately never enter Room and disappear with the process. */
    private var privateTabs: List<TabItem> = emptyList()
    private var nextPrivateId = -1L

    /** Serializes Room drains; see [flushNow] for the ordering guarantee. */
    private val flushMutex = Mutex()

    private data class PendingWrite(val url: String? = null, val title: String? = null)

    private sealed interface MetadataMutation {
        data class Url(val tabId: Long, val value: String) : MetadataMutation
        data class Title(val tabId: Long, val value: String) : MetadataMutation
        data class Flush(val completion: CompletableDeferred<Unit>) : MetadataMutation
    }

    private val pending = ConcurrentHashMap<Long, PendingWrite>()
    private var flushJob: Job? = null
    /** Preserves WebView callback order and serializes metadata with structural workspace changes. */
    private val metadataMutations = Channel<MetadataMutation>(Channel.UNLIMITED)

    init {
        appScope.launch {
            for (mutation in metadataMutations) {
                when (mutation) {
                    is MetadataMutation.Url -> applyUrlMutation(mutation.tabId, mutation.value)
                    is MetadataMutation.Title -> applyTitleMutation(mutation.tabId, mutation.value)
                    is MetadataMutation.Flush -> {
                        flushJob?.cancel()
                        flushJob = null
                        runCatching { flushNow() }
                            .onSuccess { mutation.completion.complete(Unit) }
                            .onFailure(mutation.completion::completeExceptionally)
                    }
                }
            }
        }
    }

    override suspend fun restore(): Unit = mutex.withLock {
        if (_workspace.value.isRestored) return
        val rows = dao.byPosition()
        val now = System.currentTimeMillis()

        if (rows.isEmpty()) {
            dao.insertAndActivate(homeTab(position = 0, timestamp = now))
        } else {
            if (rows.withIndex().any { (position, row) -> row.position != position }) {
                dao.reorder(rows.map { it.id })
            }
            // A persisted URL that can never load (javascript:, file:, …)
            // keeps its tab but becomes home — silently dropping tabs reads as data loss.
            rows.filter { !isSafeLoadableUrl(it.url) }.forEach { bad ->
                dao.updateUrl(bad.id, BrowserUrls.HOME)
            }
            // Self-heal the exactly-one-active invariant against corrupted persistent state.
            val actives = rows.filter { it.isActive }
            val chosen = actives.maxByOrNull { it.lastAccessedAt }
                ?: rows.maxByOrNull { it.lastAccessedAt }
            if (actives.size != 1 && chosen != null) dao.activate(chosen.id)
        }

        refreshStateLocked(isRestored = true)
    }

    override suspend fun newTab(url: String, mode: BrowsingMode): Long? = mutex.withLock {
        if (_workspace.value.tabs.size >= TabRepository.MAX_TABS) return null
        val now = System.currentTimeMillis()
        val safeUrl = url.takeIf { isSafeLoadableUrl(it) } ?: BrowserUrls.HOME
        if (mode == BrowsingMode.Private) {
            val id = nextPrivateId--
            privateTabs = (privateTabs + TabItem(
                id = id,
                url = safeUrl,
                title = "",
                position = privateTabs.size,
                isActive = true,
                createdAt = now,
                lastAccessedAt = now,
                browsingMode = BrowsingMode.Private,
            )).canonicalized()
            publishStateLocked(activeTabId = id)
            return id
        }

        val position = dao.count()
        val id = dao.insertAndActivate(
            TabEntity(
                url = safeUrl,
                position = position,
                isActive = true,
                createdAt = now,
                lastAccessedAt = now,
            )
        )
        refreshStateLocked(activeTabId = id)
        id
    }

    override suspend fun switchTo(tabId: Long): Unit = mutex.withLock {
        val target = _workspace.value.tabs.firstOrNull { it.id == tabId } ?: return
        val now = System.currentTimeMillis()
        if (target.isPrivate) {
            privateTabs = privateTabs.map { if (it.id == tabId) it.copy(lastAccessedAt = now) else it }
            publishStateLocked(activeTabId = tabId)
        } else {
            dao.activate(tabId)
            dao.touch(tabId, now)
            refreshStateLocked(activeTabId = tabId)
        }
    }

    override suspend fun pinTab(tabId: Long) = setPinned(tabId, isPinned = true)

    override suspend fun unpinTab(tabId: Long) = setPinned(tabId, isPinned = false)

    override suspend fun setTabsPinned(tabIds: Set<Long>, isPinned: Boolean): Unit = mutex.withLock {
        if (tabIds.isEmpty()) return
        val existingIds = _workspace.value.tabs.asSequence()
            .filter { it.id in tabIds }
            .mapTo(mutableSetOf()) { it.id }
        if (existingIds.isEmpty()) return

        val privateIds = existingIds.filterTo(mutableSetOf()) { it < 0L }
        if (privateIds.isNotEmpty()) {
            privateTabs = privateTabs
                .map { tab -> if (tab.id in privateIds) tab.copy(isPinned = isPinned) else tab }
                .canonicalized()
        }
        val normalIds = existingIds.filterTo(mutableSetOf()) { it >= 0L }
        if (normalIds.isNotEmpty()) {
            val reordered = dao.byPosition()
                .map { row -> if (row.id in normalIds) row.copy(isPinned = isPinned) else row }
                .canonicalized()
            dao.setPinnedAndOrder(normalIds, isPinned, reordered.map { it.id })
        }
        publishStateLocked()
    }

    override suspend fun reorderTab(tabId: Long, newPosition: Int): Unit = mutex.withLock {
        val target = _workspace.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (target.isPrivate) {
            privateTabs = privateTabs.movedWithinPinSegment(tabId, newPosition) ?: return
            publishStateLocked()
        } else {
            val reordered = dao.byPosition().movedWithinPinSegment(tabId, newPosition) ?: return
            dao.reorder(reordered.map { it.id })
            refreshStateLocked()
        }
    }

    override suspend fun closeTab(tabId: Long) {
        closeTabs(setOf(tabId))
    }

    override suspend fun closeTabs(tabIds: Set<Long>): Unit = mutex.withLock {
        if (tabIds.isEmpty()) return
        val currentWorkspace = _workspace.value
        val existingIds = currentWorkspace.tabs.asSequence()
            .filter { it.id in tabIds }
            .mapTo(mutableSetOf()) { it.id }
        if (existingIds.isEmpty()) return

        val privateIds = existingIds.filterTo(mutableSetOf()) { it < 0L }
        if (privateIds.isNotEmpty()) {
            privateTabs = privateTabs.filterNot { it.id in privateIds }.canonicalized()
        }

        val normalIds = existingIds.filterTo(mutableSetOf()) { it >= 0L }
        var persistedActiveId = dao.activeTab()?.id
        if (normalIds.isNotEmpty()) {
            val currentRows = dao.byPosition()
            val remaining = currentRows.filterNot { it.id in normalIds }
            if (remaining.isEmpty()) {
                val now = System.currentTimeMillis()
                persistedActiveId = dao.replaceWithActive(homeTab(position = 0, timestamp = now))
            } else {
                val currentActiveIndex = currentRows.indexOfFirst { it.id == persistedActiveId }
                val nextId = if (persistedActiveId in normalIds) {
                    currentRows.asSequence().drop(currentActiveIndex + 1)
                        .firstOrNull { it.id !in normalIds }?.id
                        ?: currentRows.asSequence().take(currentActiveIndex)
                            .lastOrNull { it.id !in normalIds }?.id
                } else {
                    null
                }
                dao.deleteActivateAndReorder(normalIds, nextId, remaining.map { it.id })
                if (nextId != null) persistedActiveId = nextId
            }
            normalIds.forEach(pending::remove)
        }

        val activeId = currentWorkspace.activeTabId
        val resolvedActiveId = when {
            activeId !in existingIds -> activeId
            privateTabs.isNotEmpty() && activeId in privateIds -> {
                val closedIndex = currentWorkspace.tabs
                    .filter { it.isPrivate }
                    .indexOfFirst { it.id == activeId }
                privateTabs.getOrNull(closedIndex)?.id ?: privateTabs.lastOrNull()?.id
                    ?: persistedActiveId
            }
            else -> persistedActiveId
        }
        publishStateLocked(activeTabId = resolvedActiveId)
    }

    override suspend fun closeTabs(mode: BrowsingMode): Unit = mutex.withLock {
        if (mode == BrowsingMode.Private) {
            val privateIds = privateTabs.mapTo(mutableSetOf()) { it.id }
            privateTabs = emptyList()
            val activeId = if (_workspace.value.activeTabId in privateIds) dao.activeTab()?.id
                else _workspace.value.activeTabId
            publishStateLocked(activeTabId = activeId)
            return
        }

        dao.byPosition().forEach { row -> pending.remove(row.id) }
        val now = System.currentTimeMillis()
        val id = dao.replaceWithActive(homeTab(position = 0, timestamp = now))
        val activeId = _workspace.value.activeTabId
            .takeIf { current -> privateTabs.any { it.id == current } }
            ?: id
        publishStateLocked(activeTabId = activeId)
    }

    override fun discardPrivateTabs() {
        appScope.launch { closeTabs(BrowsingMode.Private) }
    }

    override fun urlCommitted(tabId: Long, url: String) {
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return
        // The immutable workspace snapshot remains synchronous for WebView/UI callers; the ordered
        // actor below reconciles the private source list and persistence under the structural mutex.
        _workspace.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(url = url) else it })
        }
        metadataMutations.trySend(MetadataMutation.Url(tabId, url))
    }

    override fun titleReceived(tabId: Long, title: String) {
        if (title.isBlank()) return
        _workspace.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(title = title) else it })
        }
        metadataMutations.trySend(MetadataMutation.Title(tabId, title))
    }

    override fun requestFlush() {
        metadataMutations.trySend(MetadataMutation.Flush(CompletableDeferred()))
    }

    override suspend fun flushPending() {
        val completion = CompletableDeferred<Unit>()
        metadataMutations.send(MetadataMutation.Flush(completion))
        completion.await()
    }

    private suspend fun setPinned(tabId: Long, isPinned: Boolean) {
        setTabsPinned(setOf(tabId), isPinned)
    }

    private suspend fun applyUrlMutation(tabId: Long, url: String) = mutex.withLock {
        if (_workspace.value.tabs.none { it.id == tabId }) return@withLock
        if (tabId < 0) {
            privateTabs = privateTabs.map { if (it.id == tabId) it.copy(url = url) else it }
        }
        _workspace.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(url = url) else it })
        }
        if (tabId >= 0) enqueue(tabId) { copy(url = url) }
    }

    private suspend fun applyTitleMutation(tabId: Long, title: String) = mutex.withLock {
        if (_workspace.value.tabs.none { it.id == tabId }) return@withLock
        if (tabId < 0) {
            privateTabs = privateTabs.map { if (it.id == tabId) it.copy(title = title) else it }
        }
        _workspace.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(title = title) else it })
        }
        if (tabId >= 0) enqueue(tabId) { copy(title = title) }
    }

    private fun enqueue(tabId: Long, mutate: PendingWrite.() -> PendingWrite) {
        pending.compute(tabId) { _, current -> (current ?: PendingWrite()).mutate() }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = appScope.launch(dispatchers.io) {
            delay(COALESCE_WINDOW_MS)
            // The timer must NOT cancel itself first — cancelling the running
            // job would abort the writes at the next suspension point.
            flushNow()
        }
    }

    /**
     * Drains all pending writes to Room.
     *
     * Serialized on [flushMutex] so two drains can never interleave their
     * writes (which would let an older URL land after a newer one), and an
     * entry leaves [pending] only after its writes were issued — a drain
     * cancelled mid-flight therefore loses nothing: the remainder stays
     * queued for the next drain.
     */
    private suspend fun flushNow() {
        flushMutex.withLock {
            while (true) {
                val writes = HashMap(pending)
                if (writes.isEmpty()) break
                writes.forEach { (id, write) ->
                    write.url?.let { dao.updateUrl(id, it) }
                    write.title?.let { dao.updateTitle(id, it) }
                    // A newer enqueue may have replaced this entry while the
                    // writes were in flight — only remove the exact snapshot.
                    pending.remove(id, write)
                }
            }
        }
    }

    /** Re-reads Room and publishes. Callers hold [mutex]. */
    private suspend fun refreshStateLocked(
        activeTabId: Long? = _workspace.value.activeTabId,
        isRestored: Boolean = _workspace.value.isRestored,
    ) {
        val rows = dao.byPosition()
        val resolvedActiveId = activeTabId
            .takeIf { id -> privateTabs.any { it.id == id } }
            ?: dao.activeTab()?.id
        publishStateLocked(rows, resolvedActiveId, isRestored)
    }

    private suspend fun publishStateLocked(
        activeTabId: Long? = _workspace.value.activeTabId,
        isRestored: Boolean = _workspace.value.isRestored,
    ) {
        publishStateLocked(dao.byPosition(), activeTabId, isRestored)
    }

    private fun publishStateLocked(
        rows: List<TabEntity>,
        activeTabId: Long?,
        isRestored: Boolean,
    ) {
        val tabs = mergeLiveMetadata(rows.map { it.toItem(activeTabId) }) +
            privateTabs.map { it.copy(isActive = it.id == activeTabId) }
        val resolvedActiveId = activeTabId.takeIf { id -> tabs.any { it.id == id } }
            ?: rows.firstOrNull { it.isActive }?.id
            ?: tabs.firstOrNull()?.id
        _workspace.value = TabWorkspaceState(
            tabs = tabs.map { it.copy(isActive = it.id == resolvedActiveId) },
            activeTabId = resolvedActiveId,
            isRestored = isRestored,
        )
    }

    /**
     * Structural Room writes can complete before the metadata actor drains a recent WebView
     * callback. Preserve the live URL/title while republishing order, active, or pinned state.
     */
    private fun mergeLiveMetadata(rows: List<TabItem>): List<TabItem> {
        val liveById = _workspace.value.tabs.associateBy(TabItem::id)
        return rows.map { row ->
            liveById[row.id]?.let { live -> row.copy(url = live.url, title = live.title) } ?: row
        }
    }

    private fun homeTab(position: Int, timestamp: Long) = TabEntity(
        url = BrowserUrls.HOME,
        position = position,
        isActive = true,
        createdAt = timestamp,
        lastAccessedAt = timestamp,
    )

    private fun TabEntity.toItem(activeId: Long?) = TabItem(
        id = id,
        url = url,
        title = title,
        position = position,
        isActive = id == activeId,
        createdAt = createdAt,
        lastAccessedAt = lastAccessedAt,
        isPinned = isPinned,
    )

    /**
     * Pinned tabs always precede unpinned tabs; positions are then dense from zero. One
     * implementation serves both the Room rows and the process-only private items.
     */
    private fun <T> List<T>.canonicalizedBy(
        isPinned: (T) -> Boolean,
        withPosition: T.(Int) -> T,
    ): List<T> = sortedByDescending(isPinned).mapIndexed { position, tab -> tab.withPosition(position) }

    /** Moves [tabId] to [newPosition] unless the move would cross the pinned/unpinned boundary. */
    private fun <T> List<T>.movedWithinPinSegmentBy(
        tabId: Long,
        newPosition: Int,
        idOf: (T) -> Long,
        isPinned: (T) -> Boolean,
        withPosition: T.(Int) -> T,
    ): List<T>? {
        if (newPosition !in indices) return null
        val from = indexOfFirst { idOf(it) == tabId }
        if (from < 0 || isPinned(this[newPosition]) != isPinned(this[from])) return null
        return toMutableList()
            .apply { add(newPosition, removeAt(from)) }
            .mapIndexed { position, tab -> tab.withPosition(position) }
    }

    @JvmName("canonicalizedItems")
    private fun List<TabItem>.canonicalized(): List<TabItem> =
        canonicalizedBy(TabItem::isPinned) { copy(position = it) }

    @JvmName("canonicalizedEntities")
    private fun List<TabEntity>.canonicalized(): List<TabEntity> =
        canonicalizedBy(TabEntity::isPinned) { copy(position = it) }

    @JvmName("movedItemsWithinPinSegment")
    private fun List<TabItem>.movedWithinPinSegment(tabId: Long, newPosition: Int): List<TabItem>? =
        movedWithinPinSegmentBy(tabId, newPosition, TabItem::id, TabItem::isPinned) { copy(position = it) }

    @JvmName("movedEntitiesWithinPinSegment")
    private fun List<TabEntity>.movedWithinPinSegment(tabId: Long, newPosition: Int): List<TabEntity>? =
        movedWithinPinSegmentBy(tabId, newPosition, TabEntity::id, TabEntity::isPinned) { copy(position = it) }

    private companion object {
        const val COALESCE_WINDOW_MS = 800L
    }
}
