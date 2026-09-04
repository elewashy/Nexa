package com.elewashy.nexa.feature.downloads.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.domain.model.DownloadFilterCategory
import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout
import com.elewashy.nexa.feature.downloads.domain.model.DownloadSettingsDefaults
import com.elewashy.nexa.feature.downloads.presentation.components.labelRes
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppTopBar
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.components.settings.SwitchSettingsItem
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.FilterList
import com.elewashy.nexa.ui.icons.Palette
import com.elewashy.nexa.ui.icons.Settings
import com.elewashy.nexa.ui.icons.Speed
import kotlin.math.roundToInt

@Composable
fun DownloadSettingsRoute(
    onBackClick: () -> Unit,
    onDesignClick: () -> Unit,
    viewModel: DownloadSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadedState = state
    if (loadedState == null) {
        LoadingSettingsScaffold(onBackClick = onBackClick, title = stringResource(R.string.download_settings_title))
        return
    }
    DownloadSettingsScreen(
        state = loadedState,
        onBackClick = onBackClick,
        onDesignClick = onDesignClick,
        onConcurrentChange = viewModel::setMaxConcurrentDownloads,
        onFilterToggle = viewModel::toggleFilter,
        onSpeedLimitChange = viewModel::setSpeedLimit,
        onAutoRetryChange = viewModel::setAutoRetry,
        onVisualVideoPresentationChange = viewModel::setVisualVideoPresentation,
        onShowFilterCountsChange = viewModel::setShowFilterCounts,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadSettingsScreen(
    state: DownloadSettingsUiState,
    onBackClick: () -> Unit,
    onDesignClick: () -> Unit,
    onConcurrentChange: (Int) -> Unit,
    onFilterToggle: (DownloadFilterCategory) -> Unit,
    onSpeedLimitChange: (Long) -> Unit,
    onAutoRetryChange: (Boolean) -> Unit,
    onVisualVideoPresentationChange: (Boolean) -> Unit,
    onShowFilterCountsChange: (Boolean) -> Unit,
) {
    val adaptive = rememberAdaptiveLayoutInfo()
    var speedDialog by rememberSaveable { mutableStateOf<SpeedDialog?>(null) }
    var concurrentDraft by rememberSaveable(state.maxConcurrentDownloads) {
        mutableIntStateOf(state.maxConcurrentDownloads)
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.download_settings_title),
                onBackClick = onBackClick,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Scaffold's innerPadding already includes the navigation-bar inset.
            Column(
                modifier = Modifier
                    .widthIn(max = adaptive.listMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                val sections = remember(state.layout) {
                    DownloadSettingsCatalog.sectionsFor(state.layout)
                }
                sections.forEach { visibleSection ->
                    ListSection(
                        title = stringResource(visibleSection.section.titleRes()),
                        leadingContent = {
                            Icon(
                                imageVector = visibleSection.section.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    ) {
                        visibleSection.settings.forEach { setting ->
                            when (setting) {
                                DownloadSettingKey.Design -> SettingsListItem(
                                    headlineContent = stringResource(R.string.download_layout_title),
                                    supportingContent = stringResource(state.layout.labelRes),
                                    leadingContent = { ExpressiveListIcon(icon = Settings) },
                                    onClick = onDesignClick,
                                )
                                DownloadSettingKey.VideoPreviewCards -> SwitchSettingsItem(
                                    headlineContent = stringResource(R.string.visual_video_presentation),
                                    supportingContent = stringResource(R.string.visual_video_presentation_desc),
                                    checked = state.visualVideoPresentation,
                                    onCheckedChange = onVisualVideoPresentationChange,
                                )
                                DownloadSettingKey.ConcurrentDownloads -> ConcurrentDownloadsSetting(
                                    value = concurrentDraft,
                                    onValueChange = { concurrentDraft = it },
                                    onValueChangeFinished = { onConcurrentChange(concurrentDraft) },
                                )
                                DownloadSettingKey.SpeedLimit -> SettingsListItem(
                                    headlineContent = stringResource(R.string.download_speed_limit),
                                    supportingContent = speedLimitLabel(state.speedLimitBytesPerSecond),
                                    leadingContent = { ExpressiveListIcon(icon = Speed) },
                                    onClick = { speedDialog = SpeedDialog.Options },
                                )
                                DownloadSettingKey.AutomaticRetry -> SwitchSettingsItem(
                                    headlineContent = stringResource(R.string.auto_retry_downloads),
                                    supportingContent = stringResource(R.string.auto_retry_downloads_desc),
                                    checked = state.autoRetry,
                                    onCheckedChange = onAutoRetryChange,
                                )
                                DownloadSettingKey.FilterCounts -> SwitchSettingsItem(
                                    headlineContent = stringResource(R.string.download_filter_counts),
                                    supportingContent = stringResource(R.string.download_filter_counts_desc),
                                    checked = state.showFilterCounts,
                                    onCheckedChange = onShowFilterCountsChange,
                                )
                                DownloadSettingKey.FilterCategories -> {
                                    DownloadFilterCategory.entries.forEach { category ->
                                        SwitchSettingsItem(
                                            headlineContent = stringResource(category.labelRes),
                                            checked = category in state.enabledFilters,
                                            onCheckedChange = { onFilterToggle(category) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (speedDialog) {
        SpeedDialog.Options -> SpeedLimitDialog(
            selected = state.speedLimitBytesPerSecond,
            onSelect = {
                onSpeedLimitChange(it)
                speedDialog = null
            },
            onCustom = { speedDialog = SpeedDialog.Custom },
            onDismiss = { speedDialog = null },
        )
        SpeedDialog.Custom -> CustomSpeedLimitDialog(
            selected = state.speedLimitBytesPerSecond,
            onApply = {
                onSpeedLimitChange(it)
                speedDialog = null
            },
            onBack = { speedDialog = SpeedDialog.Options },
            onDismiss = { speedDialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun ConcurrentDownloadsSetting(
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.concurrent_downloads_value, value),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = DownloadSettingsDefaults.MIN_CONCURRENT_DOWNLOADS.toFloat()..
                DownloadSettingsDefaults.MAX_CONCURRENT_DOWNLOADS.toFloat(),
            steps = DownloadSettingsDefaults.MAX_CONCURRENT_DOWNLOADS -
                DownloadSettingsDefaults.MIN_CONCURRENT_DOWNLOADS - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.concurrent_downloads_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeedLimitDialog(
    selected: Long,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_speed_limit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SPEED_LIMIT_OPTIONS.forEach { value ->
                    SpeedLimitChoice(
                        label = speedLimitLabel(value),
                        selected = selected == value,
                        onClick = { onSelect(value) },
                    )
                }
                SpeedLimitChoice(
                    label = stringResource(R.string.download_speed_custom),
                    selected = selected !in SPEED_LIMIT_OPTIONS,
                    onClick = onCustom,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun CustomSpeedLimitDialog(
    selected: Long,
    onApply: (Long) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable {
        mutableStateOf(selected.takeIf { it !in SPEED_LIMIT_OPTIONS }?.div(BYTES_PER_KIB)?.toString().orEmpty())
    }
    val parsed = DownloadSettingsDefaults.parseCustomSpeedLimit(input)
    val invalid = input.isNotBlank() && parsed == null
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_speed_custom_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(MAX_CUSTOM_INPUT_CHARACTERS) },
                label = { Text(stringResource(R.string.download_speed_custom_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                input.isBlank() -> R.string.download_speed_custom_required
                                invalid -> R.string.download_speed_custom_error
                                else -> R.string.download_speed_custom_hint
                            }
                        )
                    )
                },
                isError = invalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    parsed?.let {
                        focusManager.clearFocus()
                        onApply(it)
                    }
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onApply) },
                enabled = parsed != null,
            ) { Text(stringResource(R.string.download_speed_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        },
    )
}

@Composable
private fun SpeedLimitChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private fun DownloadSettingsSection.titleRes(): Int = when (this) {
    DownloadSettingsSection.Appearance -> R.string.download_settings_appearance
    DownloadSettingsSection.Transfers -> R.string.download_settings_transfers
    DownloadSettingsSection.Filters -> R.string.download_filters_title
}

private fun DownloadSettingsSection.icon(): ImageVector = when (this) {
    DownloadSettingsSection.Appearance -> Palette
    DownloadSettingsSection.Transfers -> Download
    DownloadSettingsSection.Filters -> FilterList
}

@Composable
private fun speedLimitLabel(bytesPerSecond: Long): String = when {
    bytesPerSecond == DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND ->
        stringResource(R.string.download_speed_unlimited)
    bytesPerSecond % BYTES_PER_MIB == 0L ->
        stringResource(R.string.download_speed_mbps, bytesPerSecond / BYTES_PER_MIB)
    else -> stringResource(R.string.download_speed_kbps, (bytesPerSecond / BYTES_PER_KIB).toInt())
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingSettingsScaffold(onBackClick: () -> Unit, title: String) {
    Scaffold(
        topBar = { AppTopBar(title = title, onBackClick = onBackClick) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) { LoadingIndicator() }
    }
}

private enum class SpeedDialog { Options, Custom }

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = 1024L * BYTES_PER_KIB
private const val MAX_CUSTOM_INPUT_CHARACTERS = 16

private val SPEED_LIMIT_OPTIONS = listOf(
    DownloadSettingsDefaults.UNLIMITED_SPEED_BYTES_PER_SECOND,
    256L * BYTES_PER_KIB,
    512L * BYTES_PER_KIB,
    1L * BYTES_PER_MIB,
    2L * BYTES_PER_MIB,
    5L * BYTES_PER_MIB,
    10L * BYTES_PER_MIB,
)
