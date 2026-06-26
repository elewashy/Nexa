package com.elewashy.nexa.feature.settings.presentation.settings

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.Settings
import com.elewashy.nexa.ui.icons.Update

private data class SettingsSection(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val destination: SettingsDestination,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    viewModel: SettingsViewModel,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { scrollState.canScrollBackward || scrollState.canScrollForward }
    )

    val sections = remember {
        listOf(
            SettingsSection(R.string.general, R.string.general_description, Settings, SettingsDestination.General),
            SettingsSection(R.string.updates, R.string.updates_description, Update, SettingsDestination.Updates),
        )
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = adaptiveInfo.horizontalPadding, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SettingsListItem(
                        modifier = Modifier
                            .widthIn(max = adaptiveInfo.listMaxWidth)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large),
                        headlineContent = stringResource(R.string.about_app_name, stringResource(R.string.app_name)),
                        supportingContent = BuildConfig.VERSION_NAME,
                        leadingContent = {
                            AndroidView(
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        setImageResource(R.mipmap.ic_launcher)
                                    }
                                },
                                modifier = Modifier.size(42.dp),
                            )
                        },
                        onClick = { onNavigate(SettingsDestination.About) },
                    )
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListSection(modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth)) {
                sections.forEach { section ->
                    SettingsListItem(
                        headlineContent = stringResource(section.titleRes),
                        supportingContent = stringResource(section.descriptionRes),
                        leadingContent = { ExpressiveListIcon(icon = section.icon) },
                        onClick = { onNavigate(section.destination) },
                    )
                }
            }
        }
    }
}
