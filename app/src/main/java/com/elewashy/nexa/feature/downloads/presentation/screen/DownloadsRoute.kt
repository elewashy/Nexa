package com.elewashy.nexa.feature.downloads.presentation.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elewashy.nexa.R
import com.elewashy.nexa.core.files.DownloadDirectory
import com.elewashy.nexa.core.files.DownloadedFileIntents
import com.elewashy.nexa.core.text.limitCodePoints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.feature.downloads.data.RenameDownloadResult
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.presentation.components.DownloadItemActions
import com.elewashy.nexa.feature.downloads.presentation.settings.DownloadLayoutViewModel
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.ui.components.dialogs.ConfirmationDialog
import com.elewashy.nexa.ui.icons.Delete
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun DownloadsRoute(
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
    layoutViewModel: DownloadLayoutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val presentation by layoutViewModel.presentation.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val messageScope = rememberCoroutineScope()
    val showMessage: (String, SnackbarDuration) -> Unit = remember(snackbarHostState, messageScope) {
        { message, duration ->
            messageScope.launch { snackbarHostState.showSnackbar(message = message, duration = duration) }
        }
    }
    // One stable bundle per (viewModel, context): cards and lists receive a
    // single reference instead of ten fresh lambdas every recomposition.
    val itemActions = remember(viewModel, context, showMessage) {
        DownloadItemActions(
            onClick = { item ->
                when (val action = viewModel.onItemClick(item)) {
                    DownloadsViewModel.ItemClickAction.Handled -> Unit
                    is DownloadsViewModel.ItemClickAction.OpenFile ->
                        context.openDownloadedFile(action.item, showMessage)
                }
            },
            onLongClick = viewModel::onItemLongClick,
            onPause = { context.startDownloadControlService(DownloadService.ACTION_PAUSE_DOWNLOAD, it.id) },
            onResume = { context.startDownloadControlService(DownloadService.ACTION_RESUME_DOWNLOAD, it.id) },
            onCancel = viewModel::showCancelDialog,
            onRetry = { context.startDownloadControlService(DownloadService.ACTION_RETRY_DOWNLOAD, it.id) },
            onOpenFile = { context.openDownloadedFile(it, showMessage) },
            onRename = viewModel::showRenameDialog,
            onShare = { context.shareDownloadedFile(it, showMessage) },
            onDelete = viewModel::requestDelete,
        )
    }
    val renamedMessage = stringResource(R.string.download_renamed)
    val nameExistsMessage = stringResource(R.string.download_name_exists)
    val invalidNameMessage = stringResource(R.string.invalid_download_name)
    val renameFailedMessage = stringResource(R.string.download_rename_failed)

    // Selection mode is a transient UI layer: system back leaves it before leaving the screen.
    BackHandler(enabled = state.isMultiSelectMode, onBack = viewModel::clearSelection)

    // One-time warning when a download started while notifications are disabled.
    // showSnackbar suspends behind any queued message, so they display in order.
    LaunchedEffect(state.notificationsWarning) {
        val warning = state.notificationsWarning ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = warning, duration = SnackbarDuration.Long)
        viewModel.dismissNotificationsWarning()
    }

    DownloadsScreen(
        downloads = state.downloads,
        presentation = presentation,
        snackbarHostState = snackbarHostState,
        selectedItems = state.selectedItems,
        isMultiSelectMode = state.isMultiSelectMode,
        actions = itemActions,
        onBackClick = onBackClick,
        onSettingsClick = onSettingsClick,
        onDeleteSelected = viewModel::requestSelectedDelete,
        onClearSelection = viewModel::clearSelection,
    )

    state.renameDialogItem?.let { item ->
        RenameDownloadDialog(
            currentName = item.fileName,
            onConfirm = { name ->
                viewModel.confirmRename(name) { result ->
                    val message = when (result) {
                        RenameDownloadResult.Success -> renamedMessage
                        RenameDownloadResult.NameAlreadyExists -> nameExistsMessage
                        RenameDownloadResult.InvalidName -> invalidNameMessage
                        else -> renameFailedMessage
                    }
                    showMessage(message, SnackbarDuration.Short)
                }
            },
            onDismiss = viewModel::dismissRenameDialog,
        )
    }

    state.cancelDialogItem?.let { item ->
        val cancelMessage = stringResource(R.string.cancel_download_message, item.fileName)
        val downloadCancelled = stringResource(R.string.download_cancelled)
        ConfirmationDialog(
            title = stringResource(R.string.cancel_download),
            message = buildAnnotatedString {
                append(cancelMessage)
            },
            positiveButtonText = stringResource(R.string.yes),
            negativeButtonText = stringResource(R.string.no),
            onPositiveClick = {
                viewModel.confirmCancel()
                showMessage(downloadCancelled, SnackbarDuration.Short)
            },
            onNegativeClick = {},
            onDismiss = viewModel::dismissCancelDialog,
        )
    }

    state.deleteConfirmation?.let { confirmation ->
        DeleteDownloadsConfirmationDialog(
            confirmation = confirmation,
            onConfirm = { deletedMessage ->
                viewModel.confirmDelete()
                showMessage(deletedMessage, SnackbarDuration.Short)
            },
            onDismiss = viewModel::dismissDeleteConfirmation,
        )
    }
}

/**
 * Deleting from the Download Manager removes the file itself, so the copy states that plainly
 * and the confirming action is destructive-styled. Title, body, and feedback all derive from the
 * same [DeleteConfirmation], so single and bulk deletion cannot drift apart.
 */
@Composable
private fun DeleteDownloadsConfirmationDialog(
    confirmation: DeleteConfirmation,
    onConfirm: (deletedMessage: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val count = confirmation.count
    val singleFileName = confirmation.singleFileName
    val message = singleFileName?.let { stringResource(R.string.delete_download_message, it) }
        ?: pluralStringResource(R.plurals.delete_downloads_message, count, count)
    val deletedMessage = singleFileName?.let { stringResource(R.string.deleted_file_message, it) }
        ?: pluralStringResource(R.plurals.deleted_items_message, count, count)
    ConfirmationDialog(
        title = pluralStringResource(R.plurals.delete_downloads_title, count, count),
        message = AnnotatedString(message),
        positiveButtonText = stringResource(R.string.delete),
        negativeButtonText = stringResource(R.string.cancel),
        onPositiveClick = { onConfirm(deletedMessage) },
        onNegativeClick = {},
        onDismiss = onDismiss,
        destructive = true,
        icon = Delete,
    )
}

@Composable
private fun RenameDownloadDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val valid = name.isNotBlank() && name != currentName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_download)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.limitCodePoints(MAX_DOWNLOAD_NAME_CODE_POINTS) },
                label = { Text(stringResource(R.string.file_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun Context.shareDownloadedFile(
    item: DownloadItem,
    showMessage: (String, SnackbarDuration) -> Unit,
) {
    val file = safeDownloadedFile(item.filePath)
    if (file == null || !file.exists()) {
        showMessage(getString(R.string.file_not_found_error), SnackbarDuration.Short)
        return
    }
    try {
        val sendIntent = DownloadedFileIntents.createShareIntent(this, file, item.mimeType)
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        showMessage(getString(R.string.no_app_found_share_file), SnackbarDuration.Short)
    } catch (_: Exception) {
        showMessage(getString(R.string.share_file_failed), SnackbarDuration.Short)
    }
}

internal fun Context.openDownloadedFile(
    item: DownloadItem,
    showMessage: (String, SnackbarDuration) -> Unit,
) {
    val file = safeDownloadedFile(item.filePath)
    if (file == null || !file.exists()) {
        showMessage(getString(R.string.file_not_found_error), SnackbarDuration.Short)
        return
    }
    if (DownloadedFileIntents.isApk(file, item.mimeType)) {
        installApkFile(file, showMessage)
        return
    }
    try {
        val viewIntent = DownloadedFileIntents.createViewIntent(this, file, item.mimeType)
        startActivity(viewIntent)
    } catch (_: ActivityNotFoundException) {
        showMessage(getString(R.string.no_app_found_open_file), SnackbarDuration.Short)
    } catch (e: Exception) {
        showMessage(getString(R.string.error_opening_file, e.message.orEmpty()), SnackbarDuration.Long)
    }
}

private fun Context.installApkFile(
    file: File,
    showMessage: (String, SnackbarDuration) -> Unit,
) {
    if (!packageManager.canRequestPackageInstalls()) {
        showMessage(getString(R.string.cannot_install_apk_permission), SnackbarDuration.Long)
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.allow_apk_installs_settings), SnackbarDuration.Long)
        }
        return
    }
    try {
        val installIntent = DownloadedFileIntents.createViewIntent(this, file, DownloadedFileIntents.APK_MIME_TYPE)
        startActivity(installIntent)
    } catch (_: ActivityNotFoundException) {
        showMessage(getString(R.string.no_apk_installer_found), SnackbarDuration.Long)
    } catch (e: Exception) {
        showMessage(getString(R.string.error_installing_apk, e.message.orEmpty()), SnackbarDuration.Long)
    }
}

private fun safeDownloadedFile(path: String): File? = DownloadDirectory.resolveOwnedFile(path)

private const val MAX_DOWNLOAD_NAME_CODE_POINTS = 120

private fun Context.startDownloadControlService(action: String, id: Long) {
    try {
        startForegroundService(DownloadService.createControlIntent(this, action, id))
    } catch (e: Exception) {
        // FGS start restrictions (background limits, quota) surface as
        // IllegalStateException — a card tap must never crash the app.
        android.util.Log.w("DownloadsRoute", "Control service start denied: ${e.message}")
    }
}
