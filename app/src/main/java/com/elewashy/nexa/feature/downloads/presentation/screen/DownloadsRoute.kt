package com.elewashy.nexa.feature.downloads.presentation.screen

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elewashy.nexa.R
import com.elewashy.nexa.core.files.DownloadedFileIntents
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.ui.components.dialogs.ConfirmationDialog
import com.elewashy.nexa.ui.components.dialogs.OptionsBottomSheet
import com.elewashy.nexa.ui.components.dialogs.ThreeButtonDialog
import java.io.File

@Composable
fun DownloadsRoute(
    onBackClick: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DownloadsScreen(
        downloads = state.downloads,
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
        onMoreOptionsClick = viewModel::showMoreOptionsDialog,
        onDeleteSelected = {
            if (viewModel.uiState.value.selectedItems.isNotEmpty()) {
                viewModel.showDeleteSelectedDialog()
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

    state.moreOptionsDialogItem?.let { item ->
        val options = getOptionsForStatus(item.status)
        if (options.isNotEmpty()) {
            val optionLabels = options.map { stringResource(it.labelRes) }
            val removedFromList = stringResource(R.string.removed_from_list)
            OptionsBottomSheet(
                options = optionLabels,
                onOptionClick = { index ->
                    viewModel.dismissMoreOptionsDialog()
                    when (options[index]) {
                        DownloadOption.OPEN_FILE -> context.openDownloadedFile(item)
                        DownloadOption.DELETE_FROM_DEVICE -> context.deleteFileFromDevice(item, viewModel)
                        DownloadOption.REMOVE_FROM_LIST -> {
                            viewModel.removeFromList(item.id)
                            context.showToast(removedFromList)
                        }
                        DownloadOption.RETRY -> context.startDownloadControlService(DownloadService.ACTION_RETRY_DOWNLOAD, item.id)
                    }
                },
                onDismiss = viewModel::dismissMoreOptionsDialog,
            )
        }
    }

    if (state.deleteSelectedItems.isNotEmpty()) {
        val items = state.deleteSelectedItems
        val deleteMessage = buildAnnotatedString {
            append(
                if (items.size == 1) {
                    stringResource(R.string.delete_file_message, items[0].fileName)
                } else {
                    pluralStringResource(R.plurals.delete_items_message, items.size, items.size)
                }
            )
        }
        ThreeButtonDialog(
            title = stringResource(R.string.delete_items),
            message = deleteMessage,
            positiveButtonText = stringResource(R.string.delete_from_device),
            neutralButtonText = stringResource(R.string.remove_from_list),
            negativeButtonText = stringResource(R.string.cancel),
            onPositiveClick = {
                var deletedCount = 0
                items.forEach { item ->
                    val file = safeDownloadedFile(item.filePath)
                    if (file?.exists() == true && file.delete()) {
                        deletedCount++
                        cleanupEmptyDirectory(file.parentFile)
                    }
                    viewModel.cancelInEngine(item.id)
                }
                viewModel.onBatchOperationFinished()
                viewModel.dismissDeleteSelectedDialog()
                context.showToast(resources.getQuantityString(R.plurals.files_deleted_count, deletedCount, deletedCount, items.size))
            },
            onNeutralClick = {
                items.forEach { item -> viewModel.removeFromList(item.id) }
                viewModel.onBatchOperationFinished()
                viewModel.dismissDeleteSelectedDialog()
                context.showToast(resources.getQuantityString(R.plurals.items_removed_count, items.size, items.size))
            },
            onNegativeClick = {},
            onDismiss = viewModel::dismissDeleteSelectedDialog,
        )
    }
}

private enum class DownloadOption(val labelRes: Int) {
    OPEN_FILE(R.string.open_file),
    DELETE_FROM_DEVICE(R.string.delete_from_device),
    REMOVE_FROM_LIST(R.string.remove_from_list),
    RETRY(R.string.retry),
}

private fun getOptionsForStatus(status: DownloadStatus): List<DownloadOption> = when (status) {
    DownloadStatus.COMPLETED -> listOf(
        DownloadOption.OPEN_FILE,
        DownloadOption.DELETE_FROM_DEVICE,
        DownloadOption.REMOVE_FROM_LIST,
    )
    DownloadStatus.CANCELLED, DownloadStatus.FAILED -> listOf(
        DownloadOption.RETRY,
        DownloadOption.REMOVE_FROM_LIST,
    )
    else -> emptyList()
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

private fun Context.deleteFileFromDevice(item: DownloadItem, viewModel: DownloadsViewModel) {
    val file = safeDownloadedFile(item.filePath)
    val deleted = if (file?.exists() == true) file.delete() else file != null
    if (deleted) {
        showToast(getString(R.string.file_deleted_successfully))
        viewModel.cancelInEngine(item.id)
        cleanupEmptyDirectory(file?.parentFile)
    } else {
        showToast(getString(R.string.failed_to_delete_file))
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

private fun cleanupEmptyDirectory(directory: File?) {
    try {
        if (directory?.exists() == true && directory.isDirectory && directory.listFiles().isNullOrEmpty()) {
            directory.delete()
        }
    } catch (_: Exception) {
    }
}

private fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}
