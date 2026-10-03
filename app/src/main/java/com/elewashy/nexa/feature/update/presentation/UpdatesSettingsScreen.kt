package com.elewashy.nexa.feature.update.presentation

import android.text.format.DateFormat
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.R
import com.elewashy.nexa.core.util.relativeTime
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockStatus
import com.elewashy.nexa.feature.browser.domain.model.FilterUpdateInterval
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.components.settings.SwitchSettingsItem
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.FilterAlt
import com.elewashy.nexa.ui.icons.UpdateFilled
import com.elewashy.nexa.ui.icons.Work
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatesSettingsScreen(
    onBackClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onUpdateClick: () -> Unit,
    viewModel: UpdatesSettingsViewModel,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isChecking by rememberSaveable { mutableStateOf(false) }

    val hasUpdate by viewModel.hasUpdate.collectAsStateWithLifecycle()
    val managerVersion by viewModel.managerVersion.collectAsStateWithLifecycle()
    val updateReleasedAt by viewModel.updateReleasedAt.collectAsStateWithLifecycle()
    val preferencesState by viewModel.preferencesState.collectAsStateWithLifecycle()
    val lastFiltersUpdateTime by viewModel.lastFiltersUpdateTime.collectAsStateWithLifecycle()
    val adBlockStatus by viewModel.adBlockStatus.collectAsStateWithLifecycle()
    val filterUpdateInterval by viewModel.filterUpdateInterval.collectAsStateWithLifecycle()
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }

    val autoUpdateCheck = preferencesState?.autoUpdateCheck
    val showUpdateDialogOnLaunch = preferencesState?.showUpdateDialogOnLaunch

    val filtersUpdatedSuccessfully = stringResource(R.string.filters_updated_successfully)
    val filtersUpdateFailed = stringResource(R.string.filters_update_failed)

    val dateTimeFormats = remember(context, configuration) {
        DateFormat.getMediumDateFormat(context) to DateFormat.getTimeFormat(context)
    }

    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { listState.canScrollBackward || listState.canScrollForward }
    )

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.updates)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding()) {
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = adaptiveInfo.listMaxWidth)
                        .padding(horizontal = adaptiveInfo.horizontalPadding, vertical = 8.dp)
                        .height(56.dp),
                    enabled = !isChecking,
                    onClick = {
                        scope.launch {
                            if (hasUpdate) {
                                onUpdateClick()
                                return@launch
                            }
                            isChecking = true
                            try {
                                val appUpdateResult = viewModel.checkUpdates()
                                val filtersResult = viewModel.updateAllFilters()
                                when (appUpdateResult) {
                                    UpdatesSettingsViewModel.CheckUpdateResult.UpdateAvailable -> onUpdateClick()
                                    is UpdatesSettingsViewModel.CheckUpdateResult.RateLimited -> {
                                        snackbarHostState.showSnackbar(appUpdateResult.message)
                                    }
                                    UpdatesSettingsViewModel.CheckUpdateResult.Failed -> {
                                        snackbarHostState.showSnackbar(
                                            if (filtersResult.success) filtersUpdatedSuccessfully else filtersUpdateFailed
                                        )
                                    }
                                    UpdatesSettingsViewModel.CheckUpdateResult.UpToDate -> {
                                        snackbarHostState.showSnackbar(
                                            if (filtersResult.success) filtersUpdatedSuccessfully else filtersUpdateFailed
                                        )
                                    }
                                }
                            } finally {
                                isChecking = false
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (isChecking) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            imageVector = UpdateFilled,
                            contentDescription = null,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                isChecking -> R.string.checking_for_updates
                                hasUpdate -> R.string.view_update
                                else -> R.string.manual_update_check
                            }
                        )
                    )
                }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
            // ── App section ──────────────────────────────────────────────
            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
                title = stringResource(R.string.app_name),
                leadingContent = {
                    Icon(
                        Work,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        setImageResource(R.mipmap.ic_launcher)
                                    }
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .padding(start = 4.dp),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val availableVersion = managerVersion
                                val releasedAt = updateReleasedAt
                                val versionText = when {
                                    hasUpdate && availableVersion != null ->
                                        "${BuildConfig.VERSION_NAME} → $availableVersion"
                                    availableVersion != null && releasedAt != null ->
                                        "$availableVersion\u2002\u2022\u2002${releasedAt.relativeTime(context)}"
                                    else -> BuildConfig.VERSION_NAME
                                }
                                Text(
                                    text = versionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (hasUpdate)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            onClick = onChangelogClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(text = stringResource(R.string.changelog))
                        }
                    }
                }
            }
            }

            item {
            // ── Update settings section ──────────────────────────────────
            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
            ) {
                SwitchSettingsItem(
                    headlineContent = stringResource(R.string.update_checking_manager),
                    supportingContent = stringResource(R.string.update_checking_manager_description),
                    checked = autoUpdateCheck == true,
                    enabled = autoUpdateCheck != null,
                    onCheckedChange = { viewModel.setAutoUpdateCheck(it) },
                )

                AnimatedVisibility(visible = autoUpdateCheck == true) {
                    SwitchSettingsItem(
                        headlineContent = stringResource(R.string.show_update_dialog_on_launch),
                        supportingContent = stringResource(R.string.show_update_dialog_on_launch_description),
                        checked = showUpdateDialogOnLaunch == true,
                        enabled = showUpdateDialogOnLaunch != null,
                        onCheckedChange = { viewModel.setShowUpdateDialogOnLaunch(it) },
                    )
                }
            }
            }

            item {
            // ── Filters section ──────────────────────────────────────────
            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
                title = stringResource(R.string.filters),
                leadingContent = {
                    Icon(
                        FilterAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(52.dp),
                            ) {
                                if (isChecking || adBlockStatus.phase != AdBlockStatus.Phase.Ready) {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(52.dp),
                                    )
                                }
                                Icon(
                                    imageVector = FilterAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.update_filters),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val lastUpdateText = if (lastFiltersUpdateTime > 0L) {
                                    stringResource(
                                        R.string.last_updated,
                                        Date(lastFiltersUpdateTime).let { date ->
                                            "${dateTimeFormats.first.format(date)} ${dateTimeFormats.second.format(date)}"
                                        }
                                    )
                                } else {
                                    stringResource(R.string.never_updated)
                                }
                                Text(
                                    text = lastUpdateText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilterStatusText(adBlockStatus)
                            }
                        }
                    }
                }

                val interval = filterUpdateInterval
                SettingsListItem(
                    headlineContent = stringResource(R.string.filter_update_interval),
                    supportingContent = interval?.let { stringResource(it.labelRes()) },
                    enabled = interval != null,
                    onClick = { showIntervalDialog = true },
                )
            }
            }
        }
    }

    val selectedInterval = filterUpdateInterval
    if (showIntervalDialog && selectedInterval != null) {
        FilterUpdateIntervalDialog(
            selected = selectedInterval,
            onSelect = {
                viewModel.setFilterUpdateInterval(it)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
}

/** Rule counts, active lists and the last update's failures of the content blocker. */
@Composable
private fun FilterStatusText(status: AdBlockStatus) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val lines = buildList {
        when (status.phase) {
            AdBlockStatus.Phase.Loading -> add(stringResource(R.string.adblock_status_loading))
            AdBlockStatus.Phase.Updating -> add(stringResource(R.string.adblock_status_updating))
            AdBlockStatus.Phase.Ready -> Unit
        }
        if (status.totalFilters > 0) {
            add(
                stringResource(
                    R.string.adblock_status_rules,
                    numberFormat.format(status.networkFilters),
                    numberFormat.format(status.cosmeticFilters),
                    numberFormat.format(status.scriptletFilters),
                )
            )
        }
        if (status.enabledLists > 0) {
            add(stringResource(R.string.adblock_status_lists, status.activeLists, status.enabledLists))
        }
        if (status.failedLists > 0) {
            add(pluralStringResource(R.plurals.adblock_status_failed_lists, status.failedLists, status.failedLists))
        }
    }
    for (line in lines) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterUpdateIntervalDialog(
    selected: FilterUpdateInterval,
    onSelect: (FilterUpdateInterval) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_update_interval)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                FilterUpdateInterval.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            text = stringResource(option.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) }
        },
    )
}

private fun FilterUpdateInterval.labelRes(): Int = when (this) {
    FilterUpdateInterval.Manual -> R.string.filter_update_interval_manual
    FilterUpdateInterval.SixHours -> R.string.filter_update_interval_6h
    FilterUpdateInterval.TwelveHours -> R.string.filter_update_interval_12h
    FilterUpdateInterval.Daily -> R.string.filter_update_interval_daily
    FilterUpdateInterval.ThreeDays -> R.string.filter_update_interval_3d
    FilterUpdateInterval.Weekly -> R.string.filter_update_interval_weekly
}
