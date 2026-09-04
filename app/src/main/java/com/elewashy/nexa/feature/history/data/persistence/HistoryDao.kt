package com.elewashy.nexa.feature.history.data.persistence

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface HistoryDao {

    data class SuggestionRow(
        val url: String,
        val title: String,
        val lastVisitedAt: Long,
        val visitCount: Int,
    )

    @Query("SELECT * FROM history ORDER BY visitedAt DESC, id DESC")
    fun pagingNewest(): PagingSource<Int, HistoryEntity>

    @Query("SELECT id FROM history ORDER BY visitedAt DESC, id DESC")
    suspend fun idsNewest(): List<Long>

    @Query(
        "SELECT * FROM history WHERE " +
            "url LIKE :query ESCAPE '!' OR title LIKE :query ESCAPE '!' " +
            "ORDER BY visitedAt DESC, id DESC"
    )
    fun pagingSearch(query: String): PagingSource<Int, HistoryEntity>

    @Query(
        "SELECT id FROM history WHERE " +
            "url LIKE :query ESCAPE '!' OR title LIKE :query ESCAPE '!' " +
            "ORDER BY visitedAt DESC, id DESC"
    )
    suspend fun idsSearch(query: String): List<Long>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Insert
    suspend fun insertAll(entities: List<HistoryEntity>)

    @Query("UPDATE history SET title = :title WHERE id = :id AND :title != ''")
    suspend fun updateTitle(id: Long, title: String): Int

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntity?

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM history WHERE id IN (:ids) ORDER BY visitedAt DESC, id DESC")
    suspend fun byIds(ids: List<Long>): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun deleteByIdsAndReturn(ids: List<Long>): List<HistoryEntity> {
        // Stay below the SQLite bind-variable limit on all supported Android versions.
        val chunks = ids.chunked(MAX_BIND_IDS)
        val items = chunks.flatMap { byIds(it) }
        chunks.forEach { deleteByIds(it) }
        return items.sortedWith(compareByDescending<HistoryEntity> { it.visitedAt }.thenByDescending { it.id })
    }

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT * FROM history ORDER BY visitedAt DESC, id DESC")
    suspend fun all(): List<HistoryEntity>

    @Transaction
    suspend fun clearAndReturnItems(): List<HistoryEntity> {
        val items = all()
        clear()
        return items
    }

    @Query("DELETE FROM history WHERE visitedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY visitedAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneBeyond(keep: Int): Int

    @Query(
        "SELECT url, MAX(title) AS title, MAX(visitedAt) AS lastVisitedAt, COUNT(*) AS visitCount " +
            "FROM history GROUP BY url " +
            "ORDER BY visitCount DESC, lastVisitedAt DESC LIMIT :limit"
    )
    suspend fun frequentSuggestions(limit: Int): List<SuggestionRow>

    @Query(
        "SELECT url, MAX(title) AS title, MAX(visitedAt) AS lastVisitedAt, COUNT(*) AS visitCount " +
            "FROM history WHERE url LIKE :query ESCAPE '!' OR title LIKE :query ESCAPE '!' " +
            "GROUP BY url ORDER BY lastVisitedAt DESC LIMIT :limit"
    )
    suspend fun searchSuggestions(query: String, limit: Int): List<SuggestionRow>

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    private companion object {
        const val MAX_BIND_IDS = 500
    }
}
