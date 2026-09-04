package com.elewashy.nexa.feature.browser.data.search

import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton

interface SearchHistoryRepository {
    suspend fun record(query: String)
    suspend fun recent(limit: Int = 8): List<String>
    suspend fun matching(query: String, limit: Int = 6): List<String>
}

@Singleton
class SearchHistoryRepositoryImpl @Inject constructor(
    private val dao: SearchHistoryDao,
) : SearchHistoryRepository {
    private val lastTimestamp = AtomicLong(0)

    override suspend fun record(query: String) {
        val normalized = query.trim().replace(WHITESPACE, " ").take(MAX_QUERY_LENGTH)
        if (normalized.isBlank()) return
        val now = lastTimestamp.updateAndGet { previous ->
            maxOf(System.currentTimeMillis(), previous + 1)
        }
        dao.recordAndPrune(
            item = SearchHistoryEntity(query = normalized, searchedAt = now),
            cutoff = now - RETENTION_MS,
            keep = MAX_STORED_QUERIES,
        )
    }

    override suspend fun recent(limit: Int): List<String> = dao.recent(limit.coerceIn(1, MAX_RESULTS))

    override suspend fun matching(query: String, limit: Int): List<String> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val pattern = "%${normalized.escapeLikePattern()}%"
        return dao.matching(pattern, limit.coerceIn(1, MAX_RESULTS))
    }

    private fun String.escapeLikePattern(): String =
        replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MAX_QUERY_LENGTH = 2048
        const val MAX_RESULTS = 24
        const val MAX_STORED_QUERIES = 500
        const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}
