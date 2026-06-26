package com.elewashy.nexa.feature.update.presentation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.paging.compose.collectAsLazyPagingItems
import com.elewashy.nexa.R
import com.elewashy.nexa.core.format.LocalizedFormatters
import com.elewashy.nexa.feature.update.presentation.UpdateViewModel.State
import com.elewashy.nexa.feature.update.presentation.components.ChangelogList
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.icons.ArrowBack
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.FileDownload
import com.elewashy.nexa.ui.icons.InstallMobile
import com.elewashy.nexa.ui.icons.Refresh
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(
    viewModel: UpdateViewModel,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val changelogs = viewModel.changelogs.collectAsLazyPagingItems()

    var backPressedOnce by remember { mutableStateOf(false) }
    val pressBackAgainMsg = stringResource(R.string.press_back_again_to_cancel_update)

    BackHandler(enabled = true) {
        if (viewModel.state == State.DOWNLOADING || viewModel.state == State.CAN_INSTALL) {
            if (backPressedOnce) {
                viewModel.cancelUpdate()
                onBackClick()
            } else {
                backPressedOnce = true
                Toast.makeText(context, pressBackAgainMsg, Toast.LENGTH_SHORT).show()
            }
        } else {
            onBackClick()
        }
    }

    val buttonConfig = when (viewModel.state) {
        State.CAN_DOWNLOAD -> Triple(
            { viewModel.downloadUpdate() },
            R.string.download,
            FileDownload
        )
        State.DOWNLOADING -> Triple(
            {
                viewModel.cancelUpdate()
                onBackClick()
            },
            R.string.cancel,
            Close
        )
        State.CAN_INSTALL -> Triple(
            {
                val apkFile = viewModel.getDownloadedApkFile()
                if (apkFile != null) {
                    installApk(context, apkFile)
                }
            },
            R.string.install_update,
            InstallMobile
        )
        State.FAILED -> Triple(
            { viewModel.retryDownload() },
            R.string.retry,
            Refresh
        )
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Column {
                        Text(stringResource(viewModel.state.title))

                        if (viewModel.state == State.DOWNLOADING) {
                            val pct = (viewModel.downloadProgress * 100).toInt()
                            Text(
                                text = LocalizedFormatters.updateProgress(
                                    context,
                                    viewModel.downloadedSize,
                                    viewModel.totalSize,
                                    pct
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.state == State.DOWNLOADING || viewModel.state == State.CAN_INSTALL) {
                            if (backPressedOnce) {
                                viewModel.cancelUpdate()
                                onBackClick()
                            } else {
                                backPressedOnce = true
                                Toast.makeText(context, pressBackAgainMsg, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = adaptiveInfo.horizontalPadding, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val (onClick, textRes, icon) = buttonConfig
                    FilledTonalButton(
                        modifier = Modifier
                            .widthIn(max = adaptiveInfo.listMaxWidth)
                            .fillMaxWidth()
                            .height(56.dp),
                        onClick = onClick,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(icon, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(textRes))
                    }
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (viewModel.state == State.DOWNLOADING) {
                val animatedProgress by animateFloatAsState(
                    targetValue = viewModel.downloadProgress,
                    animationSpec = tween(),
                    label = "updateProgress"
                )
                LinearWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (viewModel.state == State.FAILED && viewModel.errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = viewModel.errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ChangelogList(
                changelogs = changelogs,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun installApk(context: Context, apkFile: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        try {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            Toast.makeText(
                context,
                context.getString(R.string.enable_unknown_apps_install),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.cannot_open_install_settings, e.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
        return
    }

    try {
        val fileUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.install_failed, e.message.orEmpty()),
            Toast.LENGTH_LONG
        ).show()
    }
}
