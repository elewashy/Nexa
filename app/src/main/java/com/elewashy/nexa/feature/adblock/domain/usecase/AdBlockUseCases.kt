package com.elewashy.nexa.feature.adblock.domain.usecase

import android.util.Log
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicyStore
import com.elewashy.nexa.feature.adblock.data.AdBlockRepository
import com.elewashy.nexa.feature.adblock.data.CustomRuleRepository
import com.elewashy.nexa.feature.adblock.data.SiteSettingsRepository
import com.elewashy.nexa.feature.adblock.data.engine.FilterRuleSyntax
import com.elewashy.nexa.feature.adblock.domain.model.FilterListInfo
import com.elewashy.nexa.feature.adblock.domain.model.FilterListState
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateInterval
import com.elewashy.nexa.feature.adblock.domain.model.RuleKind
import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Outcome of a user-requested filter update, ready to be shown. */
sealed interface FilterUpdateOutcome {
    data object UpToDate : FilterUpdateOutcome
    data class Updated(val lists: Int) : FilterUpdateOutcome

    /** Some lists failed; their previous copies stay active. */
    data class PartiallyFailed(val failed: Int) : FilterUpdateOutcome

    /** The update could not run at all; the active filters are unchanged. */
    data object Failed : FilterUpdateOutcome
}

/** Checks every enabled list now (conditional requests) and recompiles when something changed. */
class UpdateFilterListsUseCase @Inject constructor(
    private val adBlockRepository: AdBlockRepository,
    private val appPreferences: AppPreferences,
) {
    suspend operator fun invoke(): FilterUpdateOutcome {
        val interval = FilterUpdateInterval.fromStoredValue(appPreferences.filterUpdateIntervalHours.first())
        return try {
            val result = adBlockRepository.refresh(interval.intervalMs, force = true)
            when {
                result.failed > 0 -> FilterUpdateOutcome.PartiallyFailed(result.failed)
                result.updated > 0 -> FilterUpdateOutcome.Updated(result.updated)
                else -> FilterUpdateOutcome.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Filter update failed", e)
            FilterUpdateOutcome.Failed
        }
    }

    private companion object {
        const val TAG = "UpdateFilterLists"
    }
}

/** When the filters were last checked and when the next automatic check is due. */
data class FilterUpdateSchedule(
    val interval: FilterUpdateInterval,
    /** Latest successful check among enabled lists (0 = never). */
    val lastUpdatedAt: Long,
    /** Next automatic check; null in manual mode. Can be in the past (check pending). */
    val nextCheckAt: Long?,
)

class ObserveFilterUpdateScheduleUseCase @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    operator fun invoke(lists: Flow<List<FilterListInfo>>): Flow<FilterUpdateSchedule> = combine(
        appPreferences.filterUpdateIntervalHours.map(FilterUpdateInterval::fromStoredValue),
        lists,
    ) { interval, all -> schedule(interval, all) }

    companion object {
        fun schedule(interval: FilterUpdateInterval, lists: List<FilterListInfo>): FilterUpdateSchedule {
            val enabled = lists.filter { it.enabled }
            val last = enabled.maxOfOrNull { it.lastUpdatedAt } ?: 0L
            val intervalMs = interval.intervalMs
            val next = when {
                intervalMs == null || enabled.isEmpty() -> null
                // A list that was never downloaded is fetched at the next opportunity.
                enabled.any { it.state == FilterListState.Downloading || it.lastUpdatedAt == 0L } -> 0L
                else -> enabled.minOf { it.lastUpdatedAt } + intervalMs
            }
            return FilterUpdateSchedule(interval, last, next)
        }
    }
}

/** One line of custom-rule input, normalized and classified. */
data class CustomRuleDraft(val rule: String, val kind: RuleKind)

/** Turns editor input (one rule per line) into the rules that would be stored. */
class ParseCustomRulesUseCase @Inject constructor() {
    operator fun invoke(input: String, intent: FilterRuleSyntax.Intent, trusted: Boolean): List<CustomRuleDraft> =
        input.lineSequence()
            .mapNotNull { FilterRuleSyntax.normalize(it, intent) }
            .distinct()
            .map { CustomRuleDraft(it, FilterRuleSyntax.classify(it, trusted)) }
            .toList()
}

/** Adds the rules typed in the editor. Comments and unsupported lines are stored too, like uBO's "My filters". */
class AddCustomRulesUseCase @Inject constructor(
    private val repository: CustomRuleRepository,
    private val parse: ParseCustomRulesUseCase,
) {
    suspend operator fun invoke(input: String, intent: FilterRuleSyntax.Intent): CustomRuleRepository.AddResult {
        val trusted = repository.trusted.first()
        val drafts = parse(input, intent, trusted).filter { it.rule.length <= FilterRuleSyntax.MAX_RULE_LENGTH }
        return repository.add(drafts.map { it.rule })
    }
}

/**
 * Turns ad blocking on or off for the site of [pageUrl] — the browser
 * menu's per-site switch. Edits the setting that currently governs the page
 * (the site itself or a parent domain) so the switch always reflects and
 * changes what applies; otherwise creates one for the page's site.
 */
class SetSiteAdBlockingUseCase @Inject constructor(
    private val policyStore: AdBlockPolicyStore,
) {
    /** Returns the site the change applies to, or null when [pageUrl] has no site. */
    suspend operator fun invoke(pageUrl: String, enabled: Boolean): String? {
        val pageHost = SiteSettingsRepository.siteKey(pageUrl) ?: return null
        val governing = policyStore.current().governingSettings(pageHost)
        val updated = governing?.copy(blockingEnabled = enabled)
            ?: SiteAdBlockSettings(host = pageHost, blockingEnabled = enabled)
        policyStore.saveSite(updated)
        return updated.host
    }
}
