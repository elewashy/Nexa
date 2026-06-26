package com.elewashy.nexa.feature.update.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.ChangelogsRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ChangelogsViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val changelogs: Flow<PagingData<ReleaseHistoryEntry>> = Pager(
        config = PagingConfig(pageSize = 10, enablePlaceholders = false),
        pagingSourceFactory = { ChangelogsRepository(updateRepository, includePrereleases = false) }
    ).flow.cachedIn(viewModelScope)
}
