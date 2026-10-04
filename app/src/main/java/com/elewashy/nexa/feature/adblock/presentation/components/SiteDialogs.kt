package com.elewashy.nexa.feature.adblock.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close

/** Asks for a site to exempt from ad blocking. [onAdd] returns false for input that is not a site. */
@Composable
fun AddSiteDialog(
    onAdd: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = {
        if (input.isNotBlank()) {
            if (onAdd(input)) onDismiss() else invalid = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adblock_add_site)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.adblock_add_site_description), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.adblock_add_site_hint)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.adblock_add_site_invalid)) }) else null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = input.isNotBlank()) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * The switches of one site. Changes apply immediately (like Android
 * settings); element hiding and pop-up blocking only matter while ad
 * blocking is on for the site, so they are disabled otherwise.
 */
@Composable
fun SiteSettingsDialog(
    settings: SiteAdBlockSettings,
    onChange: (SiteAdBlockSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { HostText(settings.host) },
        text = {
            Column {
                DialogSwitchRow(
                    title = stringResource(R.string.adblock_site_blocking),
                    description = stringResource(R.string.adblock_site_blocking_description),
                    checked = settings.blockingEnabled,
                    onCheckedChange = { onChange(settings.copy(blockingEnabled = it)) },
                )
                DialogSwitchRow(
                    title = stringResource(R.string.adblock_site_cosmetic),
                    description = stringResource(R.string.adblock_site_cosmetic_description),
                    checked = settings.cosmeticFilteringEnabled,
                    enabled = settings.blockingEnabled,
                    onCheckedChange = { onChange(settings.copy(cosmeticFilteringEnabled = it)) },
                )
                DialogSwitchRow(
                    title = stringResource(R.string.adblock_site_popups),
                    description = stringResource(R.string.adblock_site_popups_description),
                    checked = settings.popupBlockingEnabled,
                    enabled = settings.blockingEnabled,
                    onCheckedChange = { onChange(settings.copy(popupBlockingEnabled = it)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = onReset, enabled = !settings.isDefault) {
                Text(stringResource(R.string.adblock_site_reset))
            }
        },
    )
}

@Composable
private fun DialogSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val alpha = if (enabled) 1f else DISABLED_ALPHA
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = {
                Icon(
                    imageVector = if (checked) Check else Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
