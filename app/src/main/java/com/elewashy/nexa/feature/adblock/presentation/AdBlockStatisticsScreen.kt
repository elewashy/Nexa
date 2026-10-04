package com.elewashy.nexa.feature.adblock.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatistics
import com.elewashy.nexa.feature.adblock.domain.model.BlockedDomain
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockPageScaffold
import com.elewashy.nexa.feature.adblock.presentation.components.BlockingChart
import com.elewashy.nexa.feature.adblock.presentation.components.SectionFooter
import com.elewashy.nexa.feature.adblock.presentation.components.StatFigure
import com.elewashy.nexa.feature.adblock.presentation.components.adBlockListWidth
import com.elewashy.nexa.feature.adblock.presentation.components.currentLocale
import com.elewashy.nexa.feature.adblock.presentation.components.formatBytes
import com.elewashy.nexa.feature.adblock.presentation.components.formatLocalDate
import com.elewashy.nexa.feature.adblock.presentation.components.rememberCompactCountFormat
import com.elewashy.nexa.feature.adblock.presentation.components.rememberCountFormat
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.Delete
import java.text.NumberFormat

/** Detailed blocking statistics: totals, the last week, a breakdown and the most blocked domains. */
@Composable
fun AdBlockStatisticsScreen(
    onBackClick: () -> Unit,
    viewModel: AdBlockStatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resetDone by viewModel.resetDone.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val resetMessage = stringResource(R.string.adblock_statistics_reset_done)

    LaunchedEffect(resetDone) {
        if (!resetDone) return@LaunchedEffect
        viewModel.onResetShown()
        snackbarHostState.showSnackbar(resetMessage)
    }

    val loaded = state
    AdBlockPageScaffold(
        title = stringResource(R.string.adblock_statistics),
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(
                onClick = { confirmReset = true },
                enabled = loaded != null && !loaded.statistics.isEmpty,
            ) {
                Icon(Delete, contentDescription = stringResource(R.string.adblock_reset_statistics))
            }
        },
        content = loaded?.let { ui ->
            {
                item(key = "overview") { OverviewCard(ui.statistics) }
                item(key = "breakdown") { Breakdown(ui.statistics) }
                item(key = "domains") { TopDomains(ui.topDomains) }
                item(key = "footer") {
                    SectionFooter(stringResource(R.string.adblock_stats_footer), Modifier.adBlockListWidth())
                }
            }
        },
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.adblock_reset_statistics)) },
            text = { Text(stringResource(R.string.adblock_reset_statistics_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.reset()
                }) { Text(stringResource(R.string.adblock_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun OverviewCard(statistics: AdBlockStatistics) {
    val context = LocalContext.current
    val locale = currentLocale()
    val compact = rememberCompactCountFormat()
    val full = rememberCountFormat()
    ListSection(
        modifier = Modifier.adBlockListWidth(),
        title = stringResource(R.string.adblock_section_overview),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                Text(
                    text = stringResource(R.string.adblock_stat_last_7_days),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                BlockingChart(days = statistics.recentDays)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = statistics.since?.let { stringResource(R.string.adblock_stats_since, formatLocalDate(it, locale)) }
                        ?: stringResource(R.string.adblock_stats_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Breakdown(statistics: AdBlockStatistics) {
    val full = rememberCountFormat()
    val locale = currentLocale()
    val average = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    }
    ListSection(modifier = Modifier.adBlockListWidth()) {
        ValueRow(stringResource(R.string.adblock_stat_requests), full.format(statistics.blockedRequests))
        ValueRow(stringResource(R.string.adblock_stat_popups), full.format(statistics.blockedPopups))
        ValueRow(stringResource(R.string.adblock_stat_pages), full.format(statistics.blockedPages))
        ValueRow(stringResource(R.string.adblock_stat_params), full.format(statistics.removedParams))
        ValueRow(stringResource(R.string.adblock_stat_pages_filtered), full.format(statistics.pagesFiltered))
        if (statistics.pagesFiltered > 0L) {
            val perPage = statistics.blockedRequests.toDouble() / statistics.pagesFiltered
            ValueRow(stringResource(R.string.adblock_stat_per_page), average.format(perPage))
        }
    }
}

/** A label with its figure at the end; read as one phrase by screen readers. */
@Composable
private fun ValueRow(label: String, value: String) {
    SettingsListItem(
        headlineContent = label,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@Composable
private fun TopDomains(domains: List<BlockedDomain>) {
    val full = rememberCountFormat()
    ListSection(
        modifier = Modifier.adBlockListWidth(),
        title = stringResource(R.string.adblock_top_domains),
    ) {
        if (domains.isEmpty()) {
            SettingsListItem(headlineContent = stringResource(R.string.adblock_top_domains_empty))
        }
        // Domains are punycode (ASCII), so they read correctly in RTL layouts too.
        for (domain in domains) ValueRow(domain.domain, full.format(domain.blockedCount))
    }
}
