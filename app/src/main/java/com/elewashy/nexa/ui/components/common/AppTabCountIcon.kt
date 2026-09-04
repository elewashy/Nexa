package com.elewashy.nexa.ui.components.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared browser tab-count glyph used by the toolbar and tab overview. */
@Composable
fun AppTabCountIcon(
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .border(
                width = 2.dp,
                color = color,
                shape = RoundedCornerShape(size * 0.18f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.coerceIn(0, 99).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}
