package com.elewashy.nexa.feature.browser.presentation.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.annotation.StringRes
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.presentation.webview.ContextMenuHandler
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Image as ImageIcon
import com.elewashy.nexa.ui.icons.Save
import com.elewashy.nexa.ui.icons.Share

/**
 * Defines the available context menu actions for browser long-press.
 */
enum class ContextMenuAction(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
) {
    VIEW_IMAGE(R.string.view_image, ImageIcon),
    SAVE_IMAGE(R.string.save_image, Save),
    SHARE(R.string.share, Share),
    CLOSE(R.string.close, Close)
}

@Composable
fun Base64ImageDialog(
    dataUrl: String,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(dataUrl) { ContextMenuHandler.decodeBase64Image(dataUrl) }
    val adaptiveInfo = rememberAdaptiveLayoutInfo()

    Dialog(onDismissRequest = onDismiss) {
        bitmap?.let {
            Box(
                modifier = Modifier
                    .widthIn(max = adaptiveInfo.dialogMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/**
 * Compose screen for the browser context menu bottom sheet.
 *
 * Uses Material3 [ModalBottomSheet] + [SegmentedListItem] with leading icons.
 *
 * @param actions Available context menu actions (varies by hit-test type)
 * @param onAction Callback when an action is tapped
 * @param onDismiss Callback to dismiss the sheet
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextMenuScreen(
    actions: List<ContextMenuAction>,
    onAction: (ContextMenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = adaptiveInfo.sheetMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .clip(MaterialTheme.shapes.large),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                actions.forEachIndexed { index, action ->
                    SegmentedListItem(
                        onClick = { onAction(action); onDismiss() },
                        shapes = ListItemDefaults.segmentedShapes(
                            index = index,
                            count = actions.size
                        ),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        leadingContent = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null
                            )
                        }
                    ) {
                        Text(stringResource(action.labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
