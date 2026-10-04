package com.elewashy.nexa.feature.update.presentation

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.R
import com.elewashy.nexa.core.util.relativeTime
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppSnackbarHost
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SwitchSettingsItem
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.UpdateFilled
import com.elewashy.nexa.ui.icons.Work
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatesSettingsScreen(
    onBackClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onUpdateClick: () -> Unit,
    viewModel: UpdatesSettingsViewModel,
) {
    val context = LocalContext.current
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isChecking by rememberSaveable { mutableStateOf(false) }

    val hasUpdate by viewModel.hasUpdate.collectAsStateWithLifecycle()
    val managerVersion by viewModel.managerVersion.collectAsStateWithLifecycle()
    val updateReleasedAt by viewModel.updateReleasedAt.collectAsStateWithLifecycle()
    val preferencesState by viewModel.preferencesState.collectAsStateWithLifecycle()

    val autoUpdateCheck = preferencesState?.autoUpdateCheck
    val showUpdateDialogOnLaunch = preferencesState?.showUpdateDialogOnLaunch

    val upToDateMessage = stringResource(R.string.app_up_to_date)
    val checkFailedMessage = stringResource(R.string.update_check_failed)

    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { listState.canScrollBackward || listState.canScrollForward }
    )

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.updates)) },
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
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = adaptiveInfo.listMaxWidth)
                        .padding(horizontal = adaptiveInfo.horizontalPadding, vertical = 8.dp)
                        .height(56.dp),
                    enabled = !isChecking,
                    onClick = {
                        scope.launch {
                            if (hasUpdate) {
                                onUpdateClick()
                                return@launch
                            }
                            isChecking = true
                            try {
                                when (val result = viewModel.checkUpdates()) {
                                    UpdatesSettingsViewModel.CheckUpdateResult.UpdateAvailable -> onUpdateClick()
                                    is UpdatesSettingsViewModel.CheckUpdateResult.RateLimited ->
                                        snackbarHostState.showSnackbar(result.message)
                                    UpdatesSettingsViewModel.CheckUpdateResult.Failed ->
                                        snackbarHostState.showSnackbar(checkFailedMessage)
                                    UpdatesSettingsViewModel.CheckUpdateResult.UpToDate ->
                                        snackbarHostState.showSnackbar(upToDateMessage)
                                }
                            } finally {
                                isChecking = false
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    if (isChecking) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            imageVector = UpdateFilled,
                            contentDescription = null,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                isChecking -> R.string.checking_for_updates
                                hasUpdate -> R.string.view_update
                                else -> R.string.manual_update_check
                            }
                        )
                    )
                }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
            // ── App section ──────────────────────────────────────────────
            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
                title = stringResource(R.string.app_name),
                leadingContent = {
                    Icon(
                        Work,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        setImageResource(R.mipmap.ic_launcher)
                                    }
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .padding(start = 4.dp),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val availableVersion = managerVersion
                                val releasedAt = updateReleasedAt
                                val versionText = when {
                                    hasUpdate && availableVersion != null ->
                                        "${BuildConfig.VERSION_NAME} → $availableVersion"
                                    availableVersion != null && releasedAt != null ->
                                        "$availableVersion\u2002\u2022\u2002${releasedAt.relativeTime(context)}"
                                    else -> BuildConfig.VERSION_NAME
                                }
                                Text(
                                    text = versionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (hasUpdate)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            onClick = onChangelogClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(text = stringResource(R.string.changelog))
                        }
                    }
                }
            }
            }

            item {
            // ── Update settings section ──────────────────────────────────
            ListSection(
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
            ) {
                SwitchSettingsItem(
                    headlineContent = stringResource(R.string.update_checking_manager),
                    supportingContent = stringResource(R.string.update_checking_manager_description),
                    checked = autoUpdateCheck == true,
                    enabled = autoUpdateCheck != null,
                    onCheckedChange = { viewModel.setAutoUpdateCheck(it) },
                )

                AnimatedVisibility(visible = autoUpdateCheck == true) {
                    SwitchSettingsItem(
                        headlineContent = stringResource(R.string.show_update_dialog_on_launch),
                        supportingContent = stringResource(R.string.show_update_dialog_on_launch_description),
                        checked = showUpdateDialogOnLaunch == true,
                        enabled = showUpdateDialogOnLaunch != null,
                        onCheckedChange = { viewModel.setShowUpdateDialogOnLaunch(it) },
                    )
                }
            }
            }

        }
    }
}
