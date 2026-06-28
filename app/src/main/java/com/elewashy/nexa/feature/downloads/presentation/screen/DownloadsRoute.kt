package com.elewashy.nexa.feature.downloads.presentation.screen

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elewashy.nexa.R
import com.elewashy.nexa.core.files.DownloadedFileIntents
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.ui.components.dialogs.ConfirmationDialog
import java.io.File

@Composable
fun DownloadsRoute(
    onBackClick: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingSnackbar = state.deleteSnackbarQueue.firstOrNull()
    val undoLabel = stringResource(R.string.undo)
    val deletedMessage = pendingSnackbar?.let { snackbar ->
        snackbar.fileName?.let { stringResource(R.string.deleted_file_message, it) }
            ?: stringResource(R.string.deleted_items_message, snackbar.itemCount)
    }

    LaunchedEffect(pendingSnackbar?.token) {
        val snackbar = pendingSnackbar ?: return@LaunchedEffect
        val message = deletedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Long,
        )
        viewModel.onDeleteSnackbarResult(
            token = snackbar.token,
            undo = result == SnackbarResult.ActionPerformed,
        )
    }

    DownloadsScreen(
        downloads = state.downloads,
        snackbarHostState = snackbarHostState,
        selectedItems = state.selectedItems,
        isMultiSelectMode = state.isMultiSelectMode,
        onBackClick = {
            if (!viewModel.onBackPressed()) onBackClick()
        },
        onItemClick = { item ->
            when (val action = viewModel.onItemClick(item)) {
                DownloadsViewModel.ItemClickAction.Handled -> Unit
                is DownloadsViewModel.ItemClickAction.OpenFile -> context.openDownloadedFile(action.item)
            }
        },
        onItemLongClick = viewModel::onItemLongClick,
        onPauseClick = { context.startDownloadControlService(DownloadService.ACTION_PAUSE_DOWNLOAD, it.id) },
        onResumeClick = { context.startDownloadControlService(DownloadService.ACTION_RESUME_DOWNLOAD, it.id) },
        onCancelClick = viewModel::showCancelDialog,
        onRetryClick = { context.startDownloadControlService(DownloadService.ACTION_RETRY_DOWNLOAD, it.id) },
        onOpenFileClick = { context.openDownloadedFile(it) },
        onDeleteClick = viewModel::requestDelete,
        onDeleteSelected = {
            if (viewModel.uiState.value.selectedItems.isNotEmpty()) {
                viewModel.requestSelectedDelete()
            }
        },
        onClearSelection = viewModel::clearSelection,
    )

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
                context.showToast(downloadCancelled)
            },
            onNegativeClick = {},
            onDismiss = viewModel::dismissCancelDialog,
        )
    }

}

private fun Context.openDownloadedFile(item: DownloadItem) {
    val file = safeDownloadedFile(item.filePath)
    if (file == null || !file.exists()) {
        showToast(getString(R.string.file_not_found_error))
        return
    }
    if (DownloadedFileIntents.isApk(file, item.mimeType)) {
        installApkFile(file)
        return
    }
    try {
        val viewIntent = DownloadedFileIntents.createViewIntent(this, file, item.mimeType)
        startActivity(viewIntent)
    } catch (_: ActivityNotFoundException) {
        showToast(getString(R.string.no_app_found_open_file))
    } catch (e: Exception) {
        showToast(getString(R.string.error_opening_file, e.message.orEmpty()))
    }
}

private fun Context.installApkFile(file: File) {
    if (!packageManager.canRequestPackageInstalls()) {
        showToast(getString(R.string.cannot_install_apk_permission))
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.allow_apk_installs_settings), Toast.LENGTH_LONG)
        }
        return
    }
    try {
        val installIntent = DownloadedFileIntents.createViewIntent(this, file, DownloadedFileIntents.APK_MIME_TYPE)
        startActivity(installIntent)
    } catch (_: ActivityNotFoundException) {
        showToast(getString(R.string.no_apk_installer_found), Toast.LENGTH_LONG)
    } catch (e: Exception) {
        showToast(getString(R.string.error_installing_apk, e.message.orEmpty()), Toast.LENGTH_LONG)
    }
}

private fun safeDownloadedFile(path: String): File? {
    return try {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Nexa"
        ).canonicalFile
        val file = File(path).canonicalFile
        if (file.path == root.path || file.path.startsWith(root.path + File.separator)) file else null
    } catch (_: Exception) {
        null
    }
}

private fun Context.startDownloadControlService(action: String, id: Long) {
    startForegroundService(DownloadService.createControlIntent(this, action, id))
}

private fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}
