package com.elewashy.nexa.feature.downloads.presentation.components

import androidx.compose.runtime.Immutable
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem

/**
 * Every per-item interaction a download surface can trigger.
 *
 * Bundling the callbacks keeps the two Download Manager layouts, their tab
 * contents, and the item cards on one signature: each layer forwards a single
 * stable reference instead of re-declaring ten lambdas. Cards decide from the
 * item's status which actions are exposed, so passing the complete bundle to
 * an item in any state is always safe.
 */
@Immutable
class DownloadItemActions(
    val onClick: (DownloadItem) -> Unit,
    val onLongClick: (DownloadItem) -> Unit,
    val onPause: (DownloadItem) -> Unit,
    val onResume: (DownloadItem) -> Unit,
    val onCancel: (DownloadItem) -> Unit,
    val onRetry: (DownloadItem) -> Unit,
    val onOpenFile: (DownloadItem) -> Unit,
    val onRename: (DownloadItem) -> Unit,
    val onShare: (DownloadItem) -> Unit,
    val onDelete: (DownloadItem) -> Unit,
)
