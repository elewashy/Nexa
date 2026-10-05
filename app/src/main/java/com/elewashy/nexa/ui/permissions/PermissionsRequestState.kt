package com.elewashy.nexa.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.elewashy.nexa.core.permissions.AppPermission
import com.elewashy.nexa.core.permissions.StorageAccess

/**
 * Grant state and request actions for [AppPermission.requestable], following Android's
 * permission guidance:
 *
 * - Runtime permissions (notifications on Android 13+, storage on Android 8–10) use the system
 *   dialog. Once the system stops showing it (denied twice, or "Don't ask again"), Grant opens
 *   the app's own settings page instead of silently doing nothing.
 * - Special app access (all-files access on Android 11+, install unknown apps) has no dialog;
 *   Grant opens the matching Settings page.
 * - The state is re-read whenever the screen resumes, so a grant made in Settings — or a
 *   revocation, which restarts the process — is always reflected.
 *
 * Nothing is persisted: grant state is owned by the system and read fresh.
 */
@Stable
class PermissionsRequestState internal constructor(
    private val activity: Activity,
    private val checkGranted: (Context, AppPermission) -> Boolean,
) {

    val permissions: List<AppPermission> = AppPermission.requestable

    private val granted = mutableStateMapOf<AppPermission, Boolean>()

    /** Runtime permissions whose system dialog will no longer appear; Grant opens Settings. */
    private val dialogBlocked = mutableSetOf<AppPermission>()
    private var pendingRuntime: AppPermission? = null

    internal lateinit var runtimeLauncher: ManagedActivityResultLauncher<String, Boolean>
    internal lateinit var settingsLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>

    init {
        refresh()
    }

    fun isGranted(permission: AppPermission): Boolean = granted[permission] == true

    val allGranted: Boolean get() = permissions.all(::isGranted)

    fun refresh() {
        permissions.forEach { granted[it] = checkGranted(activity, it) }
    }

    fun request(permission: AppPermission) {
        if (isGranted(permission)) return
        when (permission) {
            // All-files access is granted in Settings on Android 11+ (some OEM builds lack the
            // per-app page; the list of all apps is the fallback), a runtime dialog before that.
            AppPermission.Storage -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSettings(StorageAccess.appSettingsIntent(activity), StorageAccess.allAppsSettingsIntent())
            } else {
                requestRuntime(permission, StorageAccess.RUNTIME_PERMISSION)
            }

            AppPermission.Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestRuntime(permission, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettings(notificationSettingsIntent())
            }

            AppPermission.InstallPackages -> openSettings(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri()),
            )
        }
    }

    internal fun onRuntimeResult(isGranted: Boolean) {
        val permission = pendingRuntime
        pendingRuntime = null
        if (permission != null && !isGranted) {
            val name = runtimePermissionName(permission)
            if (name != null && !activity.shouldShowRequestPermissionRationale(name)) {
                dialogBlocked += permission
            }
        }
        refresh()
    }

    private fun requestRuntime(permission: AppPermission, name: String) {
        if (permission in dialogBlocked) {
            openSettings(
                if (permission == AppPermission.Notifications) notificationSettingsIntent() else appDetailsIntent(),
            )
            return
        }
        pendingRuntime = permission
        runtimeLauncher.launch(name)
    }

    private fun openSettings(vararg candidates: Intent) {
        for (intent in candidates) {
            try {
                settingsLauncher.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next, more generic page.
            }
        }
        // No Settings page available (heavily customized builds): try the app's details page.
        try {
            settingsLauncher.launch(appDetailsIntent())
        } catch (_: ActivityNotFoundException) {
            refresh()
        }
    }

    private fun runtimePermissionName(permission: AppPermission): String? = when (permission) {
        AppPermission.Storage -> StorageAccess.RUNTIME_PERMISSION.takeUnless { StorageAccess.isGrantedInSettings }
        AppPermission.Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        AppPermission.InstallPackages -> null
    }

    private fun packageUri() = "package:${activity.packageName}".toUri()

    private fun appDetailsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun notificationSettingsIntent() = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
}

/** Remembers a [PermissionsRequestState] for this screen and keeps it current on every resume. */
@Composable
fun rememberPermissionsRequestState(): PermissionsRequestState =
    rememberPermissionsRequestState(checkGranted = { context, permission -> permission.isGranted(context) })

/** [checkGranted] replaces the system grant checks in tests. */
@Composable
internal fun rememberPermissionsRequestState(
    checkGranted: (Context, AppPermission) -> Boolean,
): PermissionsRequestState {
    val activity = checkNotNull(LocalActivity.current) { "Permission requests need an Activity" }
    val currentCheck by rememberUpdatedState(checkGranted)
    val state = remember(activity) { PermissionsRequestState(activity) { context, p -> currentCheck(context, p) } }
    state.runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> state.onRuntimeResult(granted) }
    state.settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { state.refresh() }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}
