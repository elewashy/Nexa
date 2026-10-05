package com.elewashy.nexa.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Write access to the public Downloads/Nexa directory, which the download engine writes with
 * direct file IO. Single source of truth for the download pipeline and the permission UI.
 */
object StorageAccess {

    private const val TAG = "StorageAccess"

    /**
     *  - API 30+: MANAGE_EXTERNAL_STORAGE (Environment.isExternalStorageManager).
     *  - API 29: WRITE_EXTERNAL_STORAGE runtime permission (declared up to maxSdkVersion=29,
     *    with requestLegacyExternalStorage so direct file IO works on Android 10), or an
     *    all-files-access app-op grant.
     *  - API 26–28: legacy WRITE_EXTERNAL_STORAGE runtime permission.
     */
    fun isGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            hasWritePermission(context) || hasAllFilesAccessOnQ(context)
        else -> hasWritePermission(context)
    }

    /** Whether access is granted from a system Settings page rather than a runtime dialog. */
    val isGrantedInSettings: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The runtime permission to request when [isGrantedInSettings] is false. */
    const val RUNTIME_PERMISSION: String = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /**
     * The "All files access" Settings page for this app (API 30+). Some OEM builds lack the
     * per-app page; the caller falls back to [allAppsSettingsIntent].
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun appSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    )

    @RequiresApi(Build.VERSION_CODES.R)
    fun allAppsSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    private fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUNTIME_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * All-files-access (MANAGE_EXTERNAL_STORAGE) grant check for API 29, where
     * [Environment.isExternalStorageManager] does not exist yet — query the underlying app-op
     * directly. Unknown/ungranted ops return false.
     */
    // unsafeCheckOpNoThrow is deprecated but is the only API that can query
    // the all-files-access app-op on API 29 (isExternalStorageManager is 30+).
    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasAllFilesAccessOnQ(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            "android:manage_external_storage", Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        Log.w(TAG, "All-files-access app-op check failed: ${e.message}")
        false
    }
}
