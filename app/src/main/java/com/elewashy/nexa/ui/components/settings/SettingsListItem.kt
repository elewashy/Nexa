package com.elewashy.nexa.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close

/**
 * Exact port of the reference [ListSection] component.
 * Groups settings rows with an optional titled header, clipped to [MaterialTheme.shapes.large].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leadingContent != null) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                        leadingContent()
                    }
                }
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .clip(MaterialTheme.shapes.large),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            content()
        }
    }
}

/**
 * Exact port of the reference [SettingsListItem] — backed by [SegmentedListItem]
 * with [MaterialTheme.colorScheme.surfaceContainerLow] container color.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsListItem(
    headlineContent: String,
    modifier: Modifier = Modifier,
    supportingContent: String? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1)
    val colors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    SegmentedListItem(
        onClick = onClick ?: {},
        shapes = shapes,
        colors = colors,
        modifier = modifier,
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent?.let {
            { Box(modifier = Modifier.padding(start = 4.dp)) { it() } }
        },
        supportingContent = supportingContent?.let { { Text(it) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(headlineContent)
    }
}

/**
 * A [SettingsListItem] with a [Switch] trailing content.
 */
@Composable
fun SwitchSettingsItem(
    headlineContent: String,
    supportingContent: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
) {
    SettingsListItem(
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        modifier = modifier,
        enabled = enabled,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = if (checked) {
                    {
                        Icon(
                            imageVector = Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    {
                        Icon(
                            imageVector = Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                },
            )
        },
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
    )
}
