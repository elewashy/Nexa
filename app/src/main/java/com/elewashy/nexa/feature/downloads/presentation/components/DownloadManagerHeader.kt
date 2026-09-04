package com.elewashy.nexa.feature.downloads.presentation.components

import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.core.files.DownloadDirectory
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.core.text.limitCodePoints
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.DeleteFilled
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared Download Manager header matching the product reference in both layouts.
 * The fixed mobile spacing intentionally does not use adaptive content margins:
 * this chrome remains edge-aligned while only page content adapts on large screens.
 */
@Composable
fun DownloadManagerHeader(
    downloads: List<DownloadItem>,
    selectedCount: Int,
    isMultiSelectMode: Boolean,
    searchVisible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val totalStorage by produceState<Long?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { StatFs(DownloadDirectory.publicDownloads().path).totalBytes }.getOrNull()
        }
    }
    val usedBytes = remember(downloads) {
        downloads.asSequence()
            .filter { it.status != DownloadStatus.CANCELLED }
            .map {
                maxOf(
                    it.downloadedBytes,
                    if (it.status == DownloadStatus.COMPLETED) it.totalBytes else 0L,
                ).coerceAtLeast(0L)
            }
            .fold(0L) { total, bytes ->
                if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
            }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isMultiSelectMode) {
                        pluralStringResource(R.plurals.selected_count, selectedCount, selectedCount)
                    } else {
                        stringResource(R.string.downloads)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )

                if (isMultiSelectMode) {
                    IconButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                        Icon(
                            imageVector = DeleteFilled,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Settings,
                            contentDescription = stringResource(R.string.download_settings),
                        )
                    }
                    if (downloads.isNotEmpty()) {
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    }
                }
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }

            Text(
                text = totalStorage?.let { total ->
                    stringResource(
                        R.string.downloads_storage_usage,
                        LocalizedFormatters.fileSize(context, usedBytes),
                        LocalizedFormatters.fileSize(context, total),
                    )
                } ?: stringResource(
                    R.string.downloads_storage_used,
                    LocalizedFormatters.fileSize(context, usedBytes),
                ),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            if (searchVisible && !isMultiSelectMode) {
                TextField(
                    value = query,
                    onValueChange = { onQueryChange(it.limitCodePoints(MAX_QUERY_CODE_POINTS)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_downloads)) },
                    leadingIcon = { Icon(Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Start),
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
        }
    }
}

private const val MAX_QUERY_CODE_POINTS = 256
