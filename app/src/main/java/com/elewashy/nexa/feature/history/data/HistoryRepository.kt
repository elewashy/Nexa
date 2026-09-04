package com.elewashy.nexa.feature.history.data

import androidx.paging.PagingData
import com.elewashy.nexa.feature.history.domain.model.HistoryItem
import com.elewashy.nexa.feature.history.domain.model.HistorySuggestion
import kotlinx.coroutines.flow.Flow

/**
 * Public boundary for browsing history. Room types never cross this
 * interface; the ViewModel receives mapped [PagingData] flows and owns only
 * the caching scope.
 */
interface HistoryRepository {

    /** Paged history, newest first; a blank [query] returns everything. */
    fun observeHistory(query: String): Flow<PagingData<HistoryItem>>

    /** IDs of every row matching the current filter, used by explicit Select all. */
    suspend fun matchingIds(query: String): Set<Long>

    /** Small, deduplicated projections for the browser omnibox. */
    suspend fun frequentSuggestions(limit: Int): List<HistorySuggestion>
    suspend fun searchSuggestions(query: String, limit: Int): List<HistorySuggestion>

    /** Records a committed main-frame navigation; non-http(s) URLs ignored. */
    /** Returns the inserted visit identity, or null when suppressed/invalid. */
    suspend fun recordVisit(url: String?, isReload: Boolean): Long?

    /** Updates the exact visit produced by [recordVisit]. */
    suspend fun updateTitle(visitId: Long, title: String?)

    suspend fun delete(id: Long)

    /** Atomically deletes the requested rows and returns existing rows for Undo. */
    suspend fun delete(ids: Set<Long>): List<HistoryItem>

    /** Restores rows removed by delete operations (undo support). */
    suspend fun reinsert(items: List<HistoryItem>)

    /** Atomically returns and clears all rows so the operation remains undoable. */
    suspend fun clearAll(): List<HistoryItem>

    /** Retention policy: age cap + row cap. Safe to call at startup. */
    suspend fun prune()
}
