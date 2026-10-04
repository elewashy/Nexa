package com.elewashy.nexa.feature.adblock.presentation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.domain.model.CustomRule
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockEmptyState
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockPageScaffold
import com.elewashy.nexa.feature.adblock.presentation.components.CustomRuleEditorDialog
import com.elewashy.nexa.feature.adblock.presentation.components.RuleKindText
import com.elewashy.nexa.feature.adblock.presentation.components.RuleText
import com.elewashy.nexa.feature.adblock.presentation.components.SectionFooter
import com.elewashy.nexa.feature.adblock.presentation.components.adBlockListWidth
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SwitchSettingsItem
import com.elewashy.nexa.ui.icons.Add
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Rule

/**
 * The user's own rules (uBO "My filters"): one row per rule with its
 * effect and an on/off switch; tap to edit or delete (with undo).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CustomRulesScreen(
    onBackClick: () -> Unit,
    viewModel: CustomRulesViewModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        viewModel.onMessageShown()
        when (current) {
            is CustomRulesMessage.Added -> {
                val (added, duplicates) = current.result
                val text = buildList {
                    if (added > 0) add(resources.getQuantityString(R.plurals.adblock_rules_added, added, added))
                    if (duplicates > 0) {
                        add(resources.getQuantityString(R.plurals.adblock_rules_duplicates_skipped, duplicates, duplicates))
                    }
                }.joinToString(" · ")
                if (text.isNotEmpty()) snackbarHostState.showSnackbar(text)
            }
            is CustomRulesMessage.Deleted -> {
                val count = current.deleted.count
                val result = snackbarHostState.showSnackbar(
                    message = resources.getQuantityString(R.plurals.adblock_rules_deleted, count, count),
                    actionLabel = resources.getString(R.string.undo),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(current.deleted)
            }
            CustomRulesMessage.Duplicate -> snackbarHostState.showSnackbar(resources.getString(R.string.adblock_rule_duplicate))
        }
    }

    val loaded = state
    AdBlockPageScaffold(
        title = stringResource(R.string.adblock_custom_rules),
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        itemSpacing = ListItemDefaults.SegmentedGap,
        // Keeps the last rows clear of the floating action button.
        bottomPadding = 96.dp,
        floatingActionButton = {
            if (loaded != null) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.adblock_add_rule)) },
                )
            }
        },
        content = loaded?.let { ui ->
            {
                if (ui.rules.isEmpty()) {
                    item(key = "empty") {
                        AdBlockEmptyState(
                            icon = Rule,
                            title = stringResource(R.string.adblock_rules_empty_title),
                            description = stringResource(R.string.adblock_rules_empty_description),
                            modifier = Modifier.adBlockListWidth(),
                        )
                    }
                } else {
                    item(key = "top") { Spacer(Modifier.height(8.dp)) }
                    itemsIndexed(ui.rules, key = { _, rule -> rule.id }) { index, rule ->
                        RuleRow(
                            rule = rule,
                            index = index,
                            count = ui.rules.size,
                            onClick = { editingId = rule.id },
                            onToggle = { viewModel.setEnabled(rule.id, it) },
                            modifier = Modifier
                                .adBlockListWidth()
                                .padding(horizontal = 16.dp)
                                .animateItem(),
                        )
                    }
                }
                item(key = "advanced") {
                    ListSection(
                        modifier = Modifier.adBlockListWidth().padding(top = 16.dp),
                        title = stringResource(R.string.adblock_section_advanced),
                    ) {
                        SwitchSettingsItem(
                            headlineContent = stringResource(R.string.adblock_trust_custom_rules),
                            supportingContent = stringResource(R.string.adblock_trust_custom_rules_description),
                            checked = ui.trusted,
                            onCheckedChange = viewModel::setTrusted,
                        )
                    }
                }
                if (ui.rules.isNotEmpty()) {
                    item(key = "footer") {
                        SectionFooter(
                            stringResource(R.string.adblock_rules_empty_description),
                            Modifier.adBlockListWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    )

    if (adding) {
        CustomRuleEditorDialog(
            initialRule = null,
            preview = viewModel::preview,
            onSave = { text, intent ->
                viewModel.add(text, intent)
                adding = false
            },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
    val editing = editingId?.let { id -> loaded?.rules?.firstOrNull { it.id == id } }
    if (editing != null) {
        CustomRuleEditorDialog(
            initialRule = editing.text,
            preview = viewModel::preview,
            onSave = { text, _ ->
                viewModel.update(editing.id, text)
                editingId = null
            },
            onDelete = {
                viewModel.delete(editing.id)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuleRow(
    rule: CustomRule,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier,
        supportingContent = { RuleKindText(rule.kind) },
        trailingContent = {
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                thumbContent = {
                    Icon(
                        imageVector = if (rule.enabled) Check else Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuleText(rule.text, modifier = Modifier.alpha(if (rule.enabled) 1f else DISABLED_RULE_ALPHA))
    }
}

private const val DISABLED_RULE_ALPHA = 0.6f
