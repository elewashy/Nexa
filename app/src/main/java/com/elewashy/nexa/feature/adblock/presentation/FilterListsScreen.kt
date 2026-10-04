package com.elewashy.nexa.feature.adblock.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.util.relativeTime
import com.elewashy.nexa.feature.adblock.domain.model.AdBlockStatus
import com.elewashy.nexa.feature.adblock.domain.model.FilterListCategory
import com.elewashy.nexa.feature.adblock.domain.model.FilterListInfo
import com.elewashy.nexa.feature.adblock.domain.model.FilterListState
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockPageScaffold
import com.elewashy.nexa.feature.adblock.presentation.components.ImportFilterListDialog
import com.elewashy.nexa.feature.adblock.presentation.components.RemoveFilterListDialog
import com.elewashy.nexa.feature.adblock.presentation.components.SectionFooter
import com.elewashy.nexa.feature.adblock.presentation.components.adBlockListWidth
import com.elewashy.nexa.feature.adblock.presentation.components.labelRes
import com.elewashy.nexa.feature.adblock.presentation.components.message
import com.elewashy.nexa.feature.adblock.presentation.components.rememberCountFormat
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.Add
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Delete
import com.elewashy.nexa.ui.icons.Refresh
import java.time.Instant

/**
 * Filter-list selection, grouped by purpose like uBO's "Filter lists"
 * pane: switch lists on or off, see each list's state, update them, and
 * import or remove third-party lists.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilterListsScreen(
    onBackClick: () -> Unit,
    viewModel: FilterListsViewModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var removeKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        viewModel.onMessageShown()
        val text = when (current) {
            is FilterListsMessage.Update -> current.outcome.message(resources)
            FilterListsMessage.Imported -> resources.getString(R.string.adblock_import_added)
            FilterListsMessage.Removed -> resources.getString(R.string.adblock_list_removed)
        }
        snackbarHostState.showSnackbar(text)
    }

    val loaded = state
    val busy = updating || loaded?.status?.phase == AdBlockStatus.Phase.Updating
    val canUpdate = loaded != null && loaded.status.phase != AdBlockStatus.Phase.Disabled && !busy
    AdBlockPageScaffold(
        title = stringResource(R.string.adblock_filter_lists),
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        actions = {
            if (busy) {
                LoadingIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(24.dp))
            } else {
                IconButton(onClick = viewModel::updateNow, enabled = canUpdate) {
                    Icon(Refresh, contentDescription = stringResource(R.string.adblock_update_now))
                }
            }
            IconButton(onClick = { showImport = true }, enabled = loaded != null) {
                Icon(Add, contentDescription = stringResource(R.string.adblock_import_list))
            }
        },
        content = loaded?.let { ui ->
            {
                if (ui.status.phase == AdBlockStatus.Phase.Disabled) {
                    item(key = "disabled") {
                        SectionFooter(stringResource(R.string.adblock_update_requires_enabled), Modifier.adBlockListWidth())
                    }
                }
                for (group in ui.groups) {
                    item(key = "group:" + group.category.name) {
                        FilterListGroupSection(
                            group = group,
                            onToggle = viewModel::setEnabled,
                            onRemove = { removeKey = it },
                        )
                    }
                }
                item(key = "footer") {
                    SectionFooter(stringResource(R.string.adblock_import_list_description), Modifier.adBlockListWidth())
                }
            }
        },
    )

    if (showImport) {
        ImportFilterListDialog(
            result = importResult,
            onImport = viewModel::import,
            onResultHandled = viewModel::onImportResultHandled,
            onDismiss = {
                showImport = false
                viewModel.onImportResultHandled()
            },
        )
    }
    val removing = removeKey?.let { key -> loaded?.groups?.flatMap { it.lists }?.firstOrNull { it.key == key } }
    if (removing != null) {
        RemoveFilterListDialog(
            list = removing,
            onConfirm = {
                viewModel.remove(removing.key)
                removeKey = null
            },
            onDismiss = { removeKey = null },
        )
    }
}

/**
 * One category. Regional lists are numerous and mostly irrelevant to a
 * given user, so only the enabled ones are shown until expanded.
 */
@Composable
private fun FilterListGroupSection(
    group: FilterListGroup,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    val collapsible = group.category == FilterListCategory.Regional
    var expanded by rememberSaveable(group.category) { mutableStateOf(false) }
    val visible = if (collapsible && !expanded) group.lists.filter { it.enabled } else group.lists
    val hidden = group.lists.size - visible.size
    ListSection(
        modifier = Modifier.adBlockListWidth(),
        title = stringResource(group.category.labelRes()),
    ) {
        for (list in visible) {
            FilterListRow(
                list = list,
                onToggle = { onToggle(list.key, it) },
                onRemove = if (list.category == FilterListCategory.Imported) ({ onRemove(list.key) }) else null,
            )
        }
        if (collapsible && (hidden > 0 || expanded)) {
            SettingsListItem(
                headlineContent = if (expanded) {
                    stringResource(R.string.adblock_show_fewer_lists)
                } else {
                    pluralStringResource(R.plurals.adblock_show_more_lists, hidden, hidden)
                },
                onClick = { expanded = !expanded },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterListRow(
    list: FilterListInfo,
    onToggle: (Boolean) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val status = listStatusText(list)
    val isError = list.state == FilterListState.UpdateFailed || list.state == FilterListState.Unavailable
    val stateLabel = stringResource(if (list.enabled) R.string.adblock_list_enabled else R.string.adblock_list_disabled)
    SegmentedListItem(
        onClick = { onToggle(!list.enabled) },
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.semantics {
            role = Role.Switch
            stateDescription = stateLabel
        },
        supportingContent = status?.let {
            {
                Text(
                    text = it,
                    color = if (isError && list.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Delete, contentDescription = stringResource(R.string.adblock_remove_list))
                    }
                }
                Switch(
                    checked = list.enabled,
                    onCheckedChange = null,
                    thumbContent = {
                        Icon(
                            imageVector = if (list.enabled) Check else Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    },
                )
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(list.title)
        }
    }
}

/** "123,456 rules · updated 2 hours ago", or what is wrong with the list. */
@Composable
private fun listStatusText(list: FilterListInfo): String? {
    val context = LocalContext.current
    val counts = rememberCountFormat()
    return when (list.state) {
        FilterListState.Disabled -> list.regions.takeIf { it.isNotEmpty() }
        FilterListState.Downloading -> stringResource(R.string.adblock_list_downloading)
        FilterListState.UpdateFailed -> stringResource(R.string.adblock_list_update_failed)
        FilterListState.Unavailable -> stringResource(R.string.adblock_list_unavailable)
        FilterListState.Active -> buildList {
            list.ruleCount?.let { add(stringResource(R.string.adblock_list_rules, counts.format(it))) }
            if (list.lastUpdatedAt > 0L) {
                add(stringResource(R.string.adblock_list_updated, Instant.ofEpochMilli(list.lastUpdatedAt).relativeTime(context)))
            }
        }.joinToString(" · ").ifEmpty { null }
    }
}
