package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.ui.components.common.AppTabCountIcon
import com.elewashy.nexa.ui.components.settings.PhoneDesignSelectorScreen
import com.elewashy.nexa.ui.components.settings.PhonePreviewFrame
import com.elewashy.nexa.ui.icons.Add
import com.elewashy.nexa.ui.icons.Home
import com.elewashy.nexa.ui.icons.MoreHoriz
import com.elewashy.nexa.ui.icons.Search

@Composable
fun BrowserNavigationPositionScreen(
    selectedPosition: BrowserNavigationBarPosition,
    onPositionSelected: (BrowserNavigationBarPosition) -> Unit,
    onBackClick: () -> Unit,
    bottomBar: @Composable (() -> Unit)? = null,
) {
    PhoneDesignSelectorScreen(
        title = stringResource(R.string.navigation_bar_position),
        description = stringResource(R.string.navigation_bar_position_description),
        options = BrowserNavigationBarPosition.entries,
        selectedOption = selectedPosition,
        optionTitle = { position ->
            stringResource(
                if (position == BrowserNavigationBarPosition.Top) R.string.navigation_bar_position_top
                else R.string.navigation_bar_position_bottom
            )
        },
        optionDescription = { position ->
            stringResource(
                if (position == BrowserNavigationBarPosition.Top) R.string.navigation_bar_position_top_desc
                else R.string.navigation_bar_position_bottom_desc
            )
        },
        applyLabel = stringResource(R.string.navigation_bar_position_apply),
        appliedLabel = stringResource(R.string.download_layout_currently_applied),
        onOptionSelected = onPositionSelected,
        onBackClick = onBackClick,
        preview = { position, modifier -> BrowserPositionPhonePreview(position, modifier) },
        bottomBar = bottomBar,
    )
}

@Composable
private fun BrowserPositionPhonePreview(
    position: BrowserNavigationBarPosition,
    modifier: Modifier = Modifier,
) {
    PhonePreviewFrame(modifier) {
        val miniature = maxWidth < 130.dp
        val inset = if (miniature) 6.dp else 10.dp
        Column(Modifier.fillMaxSize().padding(inset)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(if (miniature) 34.dp else 54.dp)
                    .height(if (miniature) 3.dp else 5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Spacer(Modifier.height(if (miniature) 7.dp else 10.dp))
            if (position == BrowserNavigationBarPosition.Top) PreviewBrowserToolbar(top = true, miniature)
            PreviewWebPage(Modifier.weight(1f))
            if (position == BrowserNavigationBarPosition.Bottom) PreviewBrowserToolbar(top = false, miniature)
        }
    }
}

@Composable
private fun PreviewBrowserToolbar(top: Boolean, miniature: Boolean) {
    val barHeight = if (miniature) 26.dp else 42.dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().height(barHeight),
    ) {
        if (top) {
            Row(
                modifier = Modifier.padding(horizontal = if (miniature) 4.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (miniature) 3.dp else 5.dp),
            ) {
                PreviewNavIcon(Home, miniature)
                Surface(
                    modifier = Modifier.weight(1f).height(if (miniature) 17.dp else 28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(if (miniature) 5.dp else 8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary))
                    }
                }
                PreviewNavIcon(Add, miniature)
                PreviewNavIcon(MoreHoriz, miniature)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PreviewNavIcon(Home, miniature)
                PreviewNavIcon(Search, miniature)
                AppTabCountIcon(
                    count = 3,
                    color = MaterialTheme.colorScheme.primary,
                    size = if (miniature) 10.dp else 15.dp,
                )
                PreviewNavIcon(MoreHoriz, miniature)
            }
        }
    }
}

@Composable
private fun PreviewNavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, miniature: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(if (miniature) 10.dp else 15.dp),
    )
}

@Composable
private fun PreviewWebPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        repeat(4) { index ->
            Box(
                Modifier.fillMaxWidth(if (index % 2 == 0) 0.86f else 0.64f).height(6.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        Box(
            Modifier.fillMaxWidth().height(70.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    }
}
