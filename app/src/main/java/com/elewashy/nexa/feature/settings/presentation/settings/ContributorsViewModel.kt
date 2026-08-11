package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.feature.settings.data.GitHubContributorsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContributorsUiState(
    val isLoading: Boolean = true,
    val contributors: List<GitHubContributorsRepository.Contributor> = emptyList(),
    val isError: Boolean = false,
)

@HiltViewModel
class ContributorsViewModel @Inject constructor(
    private val contributorsRepository: GitHubContributorsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContributorsUiState())
    val uiState: StateFlow<ContributorsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() = load(refresh = true)

    private fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isError = false) }
            try {
                val contributors = contributorsRepository.getContributors(refresh)
                _uiState.update { it.copy(isLoading = false, contributors = contributors) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, isError = true) }
            }
        }
    }
}
