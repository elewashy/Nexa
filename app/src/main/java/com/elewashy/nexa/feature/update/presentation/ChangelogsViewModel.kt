package com.elewashy.nexa.feature.update.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.ChangelogsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChangelogsViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    var uiState by mutableStateOf<ChangelogsUiState>(ChangelogsUiState.Loading)
        private set

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            uiState = ChangelogsUiState.Loading
            uiState = try {
                val releases = ChangelogsRepository(updateRepository, includePrereleases = false)
                    .getReleases()
                ChangelogsUiState.Loaded(releases)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load changelogs", e)
                ChangelogsUiState.Error(e.message)
            }
        }
    }

    private companion object {
        const val TAG = "ChangelogsViewModel"
    }
}
