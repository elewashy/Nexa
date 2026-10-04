package com.elewashy.nexa.feature.adblock.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.data.engine.FilterRuleSyntax
import com.elewashy.nexa.feature.adblock.domain.model.RuleKind
import com.elewashy.nexa.feature.adblock.domain.usecase.CustomRuleDraft

/**
 * Adds rules (several at once, one per line) or edits one rule.
 *
 * The user picks whether plain input (an address or a domain) should
 * block or allow; filter syntax is kept as typed. A live preview shows
 * exactly what will be stored and what the engine will do with it, using
 * the engine's own parser.
 *
 * @param initialRule the rule being edited, or null to add new rules.
 * @param preview what [FilterRuleSyntax.normalize] + classification make of the input.
 */
@Composable
fun CustomRuleEditorDialog(
    initialRule: String?,
    preview: (String, FilterRuleSyntax.Intent) -> List<CustomRuleDraft>,
    onSave: (String, FilterRuleSyntax.Intent) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val editing = initialRule != null
    var text by rememberSaveable { mutableStateOf(initialRule.orEmpty()) }
    var intent by rememberSaveable { mutableStateOf(FilterRuleSyntax.Intent.Block) }
    val drafts = remember(text, intent) { if (text.isBlank()) emptyList() else preview(text, intent) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canSave = drafts.isNotEmpty() && (!editing || text.trim() != initialRule)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.adblock_edit_rule else R.string.adblock_add_rule)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!editing) {
                    IntentSelector(intent) { intent = it }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.adblock_rule_field)) },
                    supportingText = if (editing) null else ({ Text(stringResource(R.string.adblock_rule_field_supporting)) }),
                    minLines = if (editing) 1 else 3,
                    maxLines = if (editing) 4 else 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                RulePreview(drafts, editing)
                if (!editing && text.isBlank()) RuleExamples()
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, intent) }, enabled = canSave) {
                Text(stringResource(if (editing) R.string.save else R.string.add))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun IntentSelector(selected: FilterRuleSyntax.Intent, onSelect: (FilterRuleSyntax.Intent) -> Unit) {
    val options = FilterRuleSyntax.Intent.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            FilterRuleSyntax.Intent.Block -> R.string.adblock_rule_block
                            FilterRuleSyntax.Intent.Allow -> R.string.adblock_rule_allow
                        },
                    ),
                )
            }
        }
    }
}

/** What will be stored: the normalized rule and its effect, or a count for several lines. */
@Composable
private fun RulePreview(drafts: List<CustomRuleDraft>, editing: Boolean) {
    if (drafts.isEmpty()) return
    val single = drafts.singleOrNull()
    if (single != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!editing) {
                Text(
                    text = stringResource(R.string.adblock_rule_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RuleText(single.rule)
            }
            RuleKindText(single.kind)
        }
        return
    }
    val problems = drafts.count { !it.kind.isEffective && it.kind != RuleKind.Comment }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = pluralStringResource(R.plurals.adblock_rules_to_add, drafts.size, drafts.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (problems > 0) {
            drafts.firstOrNull { !it.kind.isEffective && it.kind != RuleKind.Comment }?.let { RuleKindText(it.kind) }
        }
    }
}

/** The effect of a rule; problems are shown in the error color. */
@Composable
fun RuleKindText(kind: RuleKind, modifier: Modifier = Modifier) {
    val problem = kind == RuleKind.Unsupported || kind == RuleKind.RequiresTrust
    Text(
        text = stringResource(kind.labelRes()),
        style = MaterialTheme.typography.bodySmall,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun RuleExamples() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.adblock_rule_examples_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Example("||ads.example.com^", stringResource(R.string.adblock_rule_example_block))
        Example("@@||example.com/player.js", stringResource(R.string.adblock_rule_example_allow))
        Example("example.com##.banner", stringResource(R.string.adblock_rule_example_hide))
    }
}

@Composable
private fun Example(rule: String, description: String) {
    Column {
        RuleText(rule, maxLines = 1)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
