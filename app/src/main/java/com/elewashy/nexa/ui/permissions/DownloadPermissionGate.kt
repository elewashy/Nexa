package com.elewashy.nexa.ui.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.core.permissions.StorageAccess
import com.elewashy.nexa.ui.components.common.AppMessages
import com.elewashy.nexa.ui.icons.FolderOpen

/**
 * Requests what a download needs at the moment the user starts one, as Android recommends for
 * runtime permissions (in context, when the feature is used). Onboarding offers the same
 * permissions up front but lets the user skip them, so this gate is what downloads rely on:
 *
 * - **Storage** (required): without it no download is started. A short rationale dialog leads
 *   to the system "All files access" page (Android 11+) or the runtime permission dialog
 *   (Android 8–10); the download starts as soon as access is granted.
 * - **Notifications** (Android 13+, optional): requested once per screen when a download starts.
 *   The download runs either way; progress then lives in the Download Manager.
 *
 * The pending download is held in memory only: granting a permission never kills the process,
 * and a denial or a dismissed dialog simply drops it.
 */
@Stable
class DownloadPermissionGate internal constructor(private val context: Context) {

    /** Download waiting for storage access; non-null while the rationale or system UI is up. */
    private var pending: (() -> Unit)? = null

    internal var showRationale by mutableStateOf(false)
        private set

    internal lateinit var settingsLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>
    internal lateinit var runtimeLauncher: ManagedActivityResultLauncher<String, Boolean>
    internal lateinit var notificationLauncher: ManagedActivityResultLauncher<String, Boolean>
    internal var onStorageDenied: (String) -> Unit = {}

    private var notificationsRequested = false

    /** Runs [download] now when storage is accessible, otherwise once the user grants access. */
    fun launch(download: () -> Unit) {
        if (StorageAccess.isGranted(context)) {
            proceed(download)
        } else {
            pending = download
            showRationale = true
        }
    }

    internal fun requestStorageAccess() {
        showRationale = false
        if (!StorageAccess.isGrantedInSettings) {
            runtimeLauncher.launch(StorageAccess.RUNTIME_PERMISSION)
            return
        }
        // Some OEM builds lack the per-app page; the list of all apps is the documented fallback.
        val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (
            tryLaunch(StorageAccess.appSettingsIntent(context)) ||
                tryLaunch(StorageAccess.allAppsSettingsIntent())
            )
        if (!opened) onStorageResult()
    }

    internal fun dismissRationale() {
        showRationale = false
        pending = null
    }

    internal fun onStorageResult() {
        val download = pending ?: return
        pending = null
        if (StorageAccess.isGranted(context)) {
            proceed(download)
        } else {
            onStorageDenied(context.getString(R.string.storage_permission_required_downloads))
        }
    }

    private fun proceed(download: () -> Unit) {
        download()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsRequested &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun tryLaunch(intent: Intent): Boolean = try {
        settingsLauncher.launch(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Remembers a [DownloadPermissionGate] for this screen. Render [DownloadPermissionRationale]
 * alongside it.
 *
 * @param onStorageDenied shows why a download did not start (defaults to the app snackbar bridge).
 */
@Composable
fun rememberDownloadPermissionGate(
    onStorageDenied: (String) -> Unit = { AppMessages.show(it) },
): DownloadPermissionGate {
    val context = LocalContext.current
    val gate = remember(context) { DownloadPermissionGate(context) }
    val currentOnStorageDenied by rememberUpdatedState(onStorageDenied)
    gate.onStorageDenied = { currentOnStorageDenied(it) }
    gate.settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { gate.onStorageResult() }
    gate.runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { gate.onStorageResult() }
    // Best effort: downloads never wait for, or depend on, the notification grant.
    gate.notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    return gate
}

/** Explains why storage access is needed before the system UI asks for it. */
@Composable
fun DownloadPermissionRationale(gate: DownloadPermissionGate) {
    if (!gate.showRationale) return
    AlertDialog(
        onDismissRequest = gate::dismissRationale,
        icon = { Icon(imageVector = FolderOpen, contentDescription = null) },
        title = { Text(stringResource(R.string.permission_storage)) },
        text = { Text(stringResource(R.string.permission_storage_description)) },
        confirmButton = {
            TextButton(onClick = gate::requestStorageAccess) {
                Text(stringResource(R.string.permission_grant))
            }
        },
        dismissButton = {
            TextButton(onClick = gate::dismissRationale) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
