package com.elewashy.nexa.feature.update.domain

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry

class ChangelogsRepository(
    private val updateRepository: UpdateRepository,
    private val includePrereleases: Boolean
) : PagingSource<Int, ReleaseHistoryEntry>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ReleaseHistoryEntry> {
        return try {
            val items = updateRepository.getReleases(includePrereleases)
            LoadResult.Page(
                data = items,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ReleaseHistoryEntry>): Int? = null
}
