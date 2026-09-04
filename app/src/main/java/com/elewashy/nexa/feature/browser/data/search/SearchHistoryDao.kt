package com.elewashy.nexa.feature.browser.data.search

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface SearchHistoryDao {
    @Upsert
    suspend fun upsert(item: SearchHistoryEntity)

    @Query("SELECT query FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<String>

    @Query(
        "SELECT query FROM search_history WHERE query LIKE :pattern ESCAPE '!' " +
            "ORDER BY searchedAt DESC LIMIT :limit"
    )
    suspend fun matching(pattern: String, limit: Int): List<String>

    @Query("DELETE FROM search_history WHERE searchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query(
        "DELETE FROM search_history WHERE query NOT IN " +
            "(SELECT query FROM search_history ORDER BY searchedAt DESC LIMIT :keep)"
    )
    suspend fun deleteBeyond(keep: Int): Int

    @Transaction
    suspend fun recordAndPrune(item: SearchHistoryEntity, cutoff: Long, keep: Int) {
        upsert(item)
        deleteOlderThan(cutoff)
        deleteBeyond(keep)
    }
}
