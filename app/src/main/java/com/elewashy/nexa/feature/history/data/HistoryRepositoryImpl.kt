package com.elewashy.nexa.feature.history.data

import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.elewashy.nexa.feature.history.data.persistence.HistoryDao
import com.elewashy.nexa.feature.history.data.persistence.HistoryEntity
import com.elewashy.nexa.feature.history.domain.model.HistoryItem
import com.elewashy.nexa.feature.history.domain.model.HistorySuggestion
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: HistoryDao,
) : HistoryRepository {

    private val insertsSincePrune = AtomicInteger(0)

    override fun observeHistory(query: String): Flow<PagingData<HistoryItem>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            if (query.isBlank()) {
                dao.pagingNewest()
            } else {
                dao.pagingSearch("%${query.escapeLikePattern()}%")
            }
        }.flow.map { pagingData -> pagingData.map { it.toItem() } }

    override suspend fun matchingIds(query: String): Set<Long> {
        val normalized = query.trim()
        val ids = if (normalized.isEmpty()) {
            dao.idsNewest()
        } else {
            dao.idsSearch("%${normalized.escapeLikePattern()}%")
        }
        return ids.toSet()
    }

    override suspend fun frequentSuggestions(limit: Int): List<HistorySuggestion> =
        dao.frequentSuggestions(limit.coerceIn(1, MAX_SUGGESTIONS)).map { it.toSuggestion() }

    override suspend fun searchSuggestions(query: String, limit: Int): List<HistorySuggestion> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return dao.searchSuggestions(
            query = "%${normalized.escapeLikePattern()}%",
            limit = limit.coerceIn(1, MAX_SUGGESTIONS),
        ).map { it.toSuggestion() }
    }

    override suspend fun recordVisit(url: String?, isReload: Boolean): Long? {
        val safe = url?.takeIf { it.isNotBlank() } ?: return null
        val scheme = safe.toUri().scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        // Refreshes re-commit the same page without user intent to "go"
        // somewhere — recording them would spam the history with duplicates.
        if (isReload) return null

        val visitId = dao.insert(HistoryEntity(url = safe, visitedAt = System.currentTimeMillis()))

        // Startup prune covers age; this bounds growth between restarts
        // without extra queries per visit or background infrastructure.
        if (insertsSincePrune.incrementAndGet() >= PRUNE_CHECK_INTERVAL) {
            insertsSincePrune.set(0)
            prune()
        }
        return visitId
    }

    override suspend fun updateTitle(visitId: Long, title: String?) {
        if (title.isNullOrBlank()) return
        dao.updateTitle(visitId, title)
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun delete(ids: Set<Long>): List<HistoryItem> {
        if (ids.isEmpty()) return emptyList()
        return dao.deleteByIdsAndReturn(ids.toList()).map { it.toItem() }
    }

    override suspend fun reinsert(items: List<HistoryItem>) {
        if (items.isEmpty()) return
        dao.insertAll(items.map { item ->
            HistoryEntity(
                id = item.id,
                url = item.url,
                title = item.title,
                visitedAt = item.visitedAt,
            )
        })
    }

    override suspend fun clearAll(): List<HistoryItem> =
        dao.clearAndReturnItems().map { it.toItem() }

    override suspend fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_AGE_MS
        dao.pruneOlderThan(cutoff)
        dao.pruneBeyond(MAX_ROWS)
    }

    private fun HistoryEntity.toItem() =
        HistoryItem(id = id, url = url, title = title, visitedAt = visitedAt)

    private fun HistoryDao.SuggestionRow.toSuggestion() = HistorySuggestion(
        url = url,
        title = title,
        lastVisitedAt = lastVisitedAt,
        visitCount = visitCount,
    )

    private fun String.escapeLikePattern(): String =
        replace("!", "!!").replace("%", "!%").replace("_", "!_")

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_SUGGESTIONS = 24
        private const val MAX_ROWS = 10_000
        private const val RETENTION_AGE_MS = 90L * 24 * 60 * 60 * 1000
        private const val PRUNE_CHECK_INTERVAL = 200
    }
}
