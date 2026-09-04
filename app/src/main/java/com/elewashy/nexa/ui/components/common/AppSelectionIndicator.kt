package com.elewashy.nexa.ui.components.common

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared Material checkbox for selecting one or more items in a collection.
 *
 * Every selectable surface (bookmarks, history, tab overview) uses this single control so the
 * touch target, checkbox semantics, and state colors stay consistent. Pass [colors] when the
 * control sits on a non-surface background, such as an active tab card's primary header.
 */
@Composable
fun AppSelectionIndicator(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CheckboxColors = CheckboxDefaults.colors(),
) {
    Checkbox(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = modifier,
        colors = colors,
    )
}
