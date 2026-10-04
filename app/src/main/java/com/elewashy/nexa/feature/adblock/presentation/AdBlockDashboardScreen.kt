package com.elewashy.nexa.feature.adblock.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.util.relativeTime
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatistics
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatus
import com.elewashy.nexa.feature.adblock.domain.usecase.FilterUpdateSchedule
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockMainSwitch
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockPageScaffold
import com.elewashy.nexa.feature.adblock.presentation.components.BlockingChart
import com.elewashy.nexa.feature.adblock.presentation.components.FilterUpdateIntervalDialog
import com.elewashy.nexa.feature.adblock.presentation.components.SectionFooter
import com.elewashy.nexa.feature.adblock.presentation.components.StatFigure
import com.elewashy.nexa.feature.adblock.presentation.components.adBlockListWidth
import com.elewashy.nexa.feature.adblock.presentation.components.currentLocale
import com.elewashy.nexa.feature.adblock.presentation.components.formatBytes
import com.elewashy.nexa.feature.adblock.presentation.components.formatDateTime
import com.elewashy.nexa.feature.adblock.presentation.components.formatLocalDate
import com.elewashy.nexa.feature.adblock.presentation.components.labelRes
import com.elewashy.nexa.feature.adblock.presentation.components.message
import com.elewashy.nexa.feature.adblock.presentation.components.rememberCompactCountFormat
import com.elewashy.nexa.feature.adblock.presentation.components.rememberCountFormat
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.BarChart
import com.elewashy.nexa.ui.icons.FormatListBulleted
import com.elewashy.nexa.ui.icons.Refresh
import com.elewashy.nexa.ui.icons.Rule
import com.elewashy.nexa.ui.icons.Schedule
import com.elewashy.nexa.ui.icons.Warning
import com.elewashy.nexa.ui.icons.WebAssetOff
import java.time.Instant

/**
 * Ad blocker home: the master switch and its status, a compact statistics
 * summary, filter-list management and update scheduling, and entry points
 * to custom rules and per-site settings.
 */
@Composable
fun AdBlockDashboardScreen(
    onBackClick: () -> Unit,
    onNavigate: (AdBlockDestination) -> Unit,
    viewModel: AdBlockDashboardViewModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val outcome by viewModel.updateOutcome.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(outcome) {
        val current = outcome ?: return@LaunchedEffect
        viewModel.onUpdateOutcomeShown()
        snackbarHostState.showSnackbar(current.message(resources))
    }

    val loaded = state
    AdBlockPageScaffold(
        title = stringResource(R.string.adblock_title),
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        content = loaded?.let { ui ->
            {
                item(key = "switch") {
                    MainSwitchSection(ui.enabled, ui.status, viewModel::setEnabled)
                }
                item(key = "statistics") {
                    StatisticsSection(ui.statistics) { onNavigate(AdBlockDestination.Statistics) }
                }
                item(key = "filters") {
                    FiltersSection(
                        ui = ui,
                        updating = updating || ui.status.phase == AdBlockStatus.Phase.Updating,
                        onFilterLists = { onNavigate(AdBlockDestination.FilterLists) },
                        onUpdateNow = viewModel::updateNow,
                        onInterval = { showIntervalDialog = true },
                    )
                }
                item(key = "rules") {
                    RulesSection(
                        customRuleCount = ui.customRuleCount,
                        siteCount = ui.siteCount,
                        onCustomRules = { onNavigate(AdBlockDestination.CustomRules) },
                        onSites = { onNavigate(AdBlockDestination.Sites) },
                    )
                }
                item(key = "footer") {
                    SectionFooter(stringResource(R.string.adblock_footer), Modifier.adBlockListWidth())
                }
            }
        },
    )

    if (showIntervalDialog && loaded != null) {
        FilterUpdateIntervalDialog(
            selected = loaded.schedule.interval,
            onSelect = {
                viewModel.setUpdateInterval(it)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
}

@Composable
private fun MainSwitchSection(
    enabled: Boolean,
    status: AdBlockStatus,
    onEnabledChange: (Boolean) -> Unit,
) {
    val compact = rememberCompactCountFormat()
    Column(
        modifier = Modifier
            .adBlockListWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
    ) {
        AdBlockMainSwitch(
            title = stringResource(R.string.adblock_main_switch),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        val stateText = when {
            !enabled -> stringResource(R.string.adblock_state_off)
            status.phase == AdBlockStatus.Phase.Loading -> stringResource(R.string.adblock_state_loading)
            status.phase == AdBlockStatus.Phase.Updating -> stringResource(R.string.adblock_state_updating)
            else -> stringResource(R.string.adblock_state_active)
        }
        val rulesText = if (enabled && status.totalFilters > 0) {
            stringResource(
                R.string.adblock_rules_summary,
                compact(status.totalFilters.toLong()),
                pluralStringResource(R.plurals.adblock_lists_count, status.activeLists, status.activeLists.toString()),
            )
        } else {
            null
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = stateText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (rulesText != null) {
                Text(
                    text = rulesText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatisticsSection(statistics: AdBlockStatistics, onAllStatistics: () -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    val compact = rememberCompactCountFormat()
    val full = rememberCountFormat()
    ListSection(
        modifier = Modifier.adBlockListWidth(),
        title = stringResource(R.string.adblock_section_statistics),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                if (statistics.isEmpty) {
                    Text(
                        text = stringResource(R.string.adblock_stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                val today = statistics.today?.blockedRequests ?: 0L
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatFigure(
                        value = compact(today),
                        label = stringResource(R.string.adblock_stat_today),
                        accessibilityValue = full.format(today),
                        modifier = Modifier.weight(1f),
                    )
                    StatFigure(
                        value = compact(statistics.totalBlocked),
                        label = stringResource(R.string.adblock_stat_total),
                        accessibilityValue = full.format(statistics.totalBlocked),
                        modifier = Modifier.weight(1f),
                    )
                    val saved = formatBytes(context, statistics.bytesSaved)
                    StatFigure(
                        value = saved,
                        label = stringResource(R.string.adblock_stat_data_saved),
                        accessibilityValue = saved,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(20.dp))
                BlockingChart(days = statistics.recentDays)
                statistics.since?.let { since ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.adblock_stats_since, formatLocalDate(since, locale)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingsListItem(
            headlineContent = stringResource(R.string.adblock_view_statistics),
            supportingContent = stringResource(R.string.adblock_view_statistics_description),
            leadingContent = { ExpressiveListIcon(icon = BarChart) },
            onClick = onAllStatistics,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FiltersSection(
    ui: AdBlockDashboardUiState,
    updating: Boolean,
    onFilterLists: () -> Unit,
    onUpdateNow: () -> Unit,
    onInterval: () -> Unit,
) {
    ListSection(
        modifier = Modifier.adBlockListWidth(),
        title = stringResource(R.string.adblock_section_filters),
    ) {
        SettingsListItem(
            headlineContent = stringResource(R.string.adblock_filter_lists),
            supportingContent = stringResource(R.string.adblock_filter_lists_summary, ui.lists.enabled, ui.lists.total),
            leadingContent = { ExpressiveListIcon(icon = FormatListBulleted) },
            onClick = onFilterLists,
        )
        AnimatedVisibility(visible = ui.lists.failed > 0) {
            SettingsListItem(
                headlineContent = pluralStringResource(R.plurals.adblock_failed_lists_title, ui.lists.failed, ui.lists.failed),
                supportingContent = stringResource(R.string.adblock_failed_lists_description),
                leadingContent = {
                    ExpressiveListIcon(
                        icon = Warning,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        iconColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                onClick = onFilterLists,
            )
        }
        SettingsListItem(
            headlineContent = stringResource(R.string.adblock_update_now),
            supportingContent = if (ui.enabled) scheduleText(ui.schedule) else stringResource(R.string.adblock_update_requires_enabled),
            leadingContent = {
                if (updating) {
                    LoadingIndicator(modifier = Modifier.size(42.dp))
                } else {
                    ExpressiveListIcon(icon = Refresh)
                }
            },
            enabled = ui.enabled && !updating,
            onClick = onUpdateNow,
        )
        SettingsListItem(
            headlineContent = stringResource(R.string.filter_update_interval),
            supportingContent = stringResource(ui.schedule.interval.labelRes()),
            leadingContent = { ExpressiveListIcon(icon = Schedule) },
            onClick = onInterval,
        )
    }
}

/** "Last updated 2 hours ago · Next check Oct 5, 18:30". */
@Composable
private fun scheduleText(schedule: FilterUpdateSchedule): String {
    val context = LocalContext.current
    val locale = currentLocale()
    val last = if (schedule.lastUpdatedAt > 0L) {
        stringResource(R.string.adblock_last_updated, Instant.ofEpochMilli(schedule.lastUpdatedAt).relativeTime(context))
    } else {
        stringResource(R.string.adblock_never_updated)
    }
    val nextAt = schedule.nextCheckAt ?: return last
    val next = if (nextAt <= System.currentTimeMillis()) {
        stringResource(R.string.adblock_next_update_soon)
    } else {
        stringResource(R.string.adblock_next_update, formatDateTime(context, nextAt, locale))
    }
    return "$last · $next"
}

@Composable
private fun RulesSection(
    customRuleCount: Int,
    siteCount: Int,
    onCustomRules: () -> Unit,
    onSites: () -> Unit,
) {
    ListSection(
        modifier = Modifier.adBlockListWidth(),
        title = stringResource(R.string.adblock_section_rules),
    ) {
        SettingsListItem(
            headlineContent = stringResource(R.string.adblock_custom_rules),
            supportingContent = if (customRuleCount > 0) {
                pluralStringResource(R.plurals.adblock_custom_rules_count, customRuleCount, customRuleCount)
            } else {
                stringResource(R.string.adblock_custom_rules_empty_summary)
            },
            leadingContent = { ExpressiveListIcon(icon = Rule) },
            onClick = onCustomRules,
        )
        SettingsListItem(
            headlineContent = stringResource(R.string.adblock_sites),
            supportingContent = if (siteCount > 0) {
                pluralStringResource(R.plurals.adblock_sites_count, siteCount, siteCount)
            } else {
                stringResource(R.string.adblock_sites_empty_summary)
            },
            leadingContent = { ExpressiveListIcon(icon = WebAssetOff) },
            onClick = onSites,
        )
    }
}
