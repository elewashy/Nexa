package com.elewashy.nexa.feature.browser.data.search

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per normalized user query; repeating a query moves it to the top. */
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["searchedAt"])],
)
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)
