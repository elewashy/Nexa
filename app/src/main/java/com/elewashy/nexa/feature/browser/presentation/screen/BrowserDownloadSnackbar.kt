package com.elewashy.nexa.feature.browser.presentation.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.ui.components.common.AppSnackbarHost

/** Browser adapter for the app-wide transient-message host. */
@Composable
fun BrowserSnackbarHost(
    hostState: SnackbarHostState,
    bottomOffset: Dp,
    modifier: Modifier = Modifier,
) {
    AppSnackbarHost(
        hostState = hostState,
        modifier = modifier,
        bottomPadding = bottomOffset,
    )
}

object BrowserDownloadSnackbarDefaults {
    val EdgeMargin = 8.dp
    val BottomOffsetWithNavBar = 60.dp // 52dp browser nav bar + 8dp Material margin.
}
