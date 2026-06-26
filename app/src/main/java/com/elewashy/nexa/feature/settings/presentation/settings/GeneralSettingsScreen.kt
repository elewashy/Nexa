package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.Language
import com.elewashy.nexa.ui.icons.Palette
import com.elewashy.nexa.ui.icons.Speed

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GeneralSettingsScreen(
    onBackClick: () -> Unit,
    onCustomizeThemeClick: () -> Unit,
    onLanguageClick: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val highRefreshRate by viewModel.highRefreshRate.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { scrollState.canScrollBackward || scrollState.canScrollForward }
    )

    val animatedSurfaceColor = animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "surface",
    ).value

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.general)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = animatedSurfaceColor,
                    scrolledContainerColor = animatedSurfaceColor,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = animatedSurfaceColor,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
                title = stringResource(R.string.experience),
                leadingContent = {
                    Icon(
                        Speed,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                SettingsListItem(
                    headlineContent = stringResource(R.string.enable_high_refresh_rate),
                    supportingContent = if (viewModel.highRefreshRateSupported) {
                        stringResource(R.string.enable_high_refresh_rate_description)
                    } else {
                        stringResource(R.string.high_refresh_rate_unsupported)
                    },
                    leadingContent = { ExpressiveListIcon(icon = Speed) },
                    trailingContent = {
                        Switch(
                            checked = highRefreshRate && viewModel.highRefreshRateSupported,
                            enabled = viewModel.highRefreshRateSupported,
                            onCheckedChange = null,
                        )
                    },
                    enabled = viewModel.highRefreshRateSupported,
                    onClick = if (viewModel.highRefreshRateSupported) {
                        { viewModel.setHighRefreshRate(!highRefreshRate) }
                    } else {
                        null
                    },
                )
                SettingsListItem(
                    headlineContent = stringResource(R.string.customize_theme),
                    supportingContent = stringResource(R.string.customize_theme_description),
                    leadingContent = { ExpressiveListIcon(icon = Palette) },
                    onClick = onCustomizeThemeClick,
                )
            }

            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
                title = stringResource(R.string.language),
                leadingContent = {
                    Icon(
                        Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                SettingsListItem(
                    headlineContent = stringResource(R.string.app_language),
                    supportingContent = currentLanguage.nativeName ?: stringResource(currentLanguage.labelRes),
                    leadingContent = { ExpressiveListIcon(icon = Language) },
                    onClick = onLanguageClick,
                )
            }
        }
    }
}
