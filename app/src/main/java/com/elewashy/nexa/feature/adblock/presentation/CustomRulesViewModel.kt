package com.elewashy.nexa.feature.adblock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.CustomRuleRepository
import com.elewashy.nexa.feature.adblock.data.engine.FilterRuleSyntax
import com.elewashy.nexa.feature.adblock.domain.model.CustomRule
import com.elewashy.nexa.feature.adblock.domain.usecase.AddCustomRulesUseCase
import com.elewashy.nexa.feature.adblock.domain.usecase.CustomRuleDraft
import com.elewashy.nexa.feature.adblock.domain.usecase.ParseCustomRulesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomRulesUiState(
    val rules: List<CustomRule>,
    val trusted: Boolean,
)

sealed interface CustomRulesMessage {
    data class Added(val result: CustomRuleRepository.AddResult) : CustomRulesMessage
    data class Deleted(val deleted: CustomRuleRepository.DeletedRules) : CustomRulesMessage
    data object Duplicate : CustomRulesMessage
}

@HiltViewModel
class CustomRulesViewModel @Inject constructor(
    private val repository: CustomRuleRepository,
    private val appPreferences: AppPreferences,
    private val addRules: AddCustomRulesUseCase,
    private val parseRules: ParseCustomRulesUseCase,
) : ViewModel() {

    val uiState: StateFlow<CustomRulesUiState?> = combine(repository.rules, repository.trusted, ::CustomRulesUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<CustomRulesMessage?>(null)
    val message: StateFlow<CustomRulesMessage?> = _message.asStateFlow()

    /** What the editor input would store; pure and cheap, called while typing. */
    fun preview(input: String, intent: FilterRuleSyntax.Intent): List<CustomRuleDraft> =
        parseRules(input, intent, uiState.value?.trusted == true)

    fun add(input: String, intent: FilterRuleSyntax.Intent) {
        viewModelScope.launch { _message.value = CustomRulesMessage.Added(addRules(input, intent)) }
    }

    fun update(id: Long, text: String) {
        viewModelScope.launch {
            val normalized = FilterRuleSyntax.normalize(text, FilterRuleSyntax.Intent.Block) ?: return@launch
            if (repository.update(id, normalized) == CustomRuleRepository.UpdateResult.Duplicate) {
                _message.value = CustomRulesMessage.Duplicate
            }
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { _message.value = CustomRulesMessage.Deleted(repository.delete(listOf(id))) }
    }

    fun undoDelete(deleted: CustomRuleRepository.DeletedRules) {
        viewModelScope.launch { repository.restore(deleted) }
    }

    fun setTrusted(trusted: Boolean) {
        viewModelScope.launch { appPreferences.setAdBlockTrustCustomRules(trusted) }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
