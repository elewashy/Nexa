package com.elewashy.nexa.feature.onboarding

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel for the first-launch onboarding permissions screen.
 *
 * Tracks the grant state of each permission the app needs and exposes
 * [allPermissionsGranted] so the UI can decide whether to enable the
 * "Continue" button or show a "Skip" option.
 *
 * Mirrors the reference project's `OnboardingViewModel` permission flow.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val app: Application
) : ViewModel() {

    var hasStoragePermission by mutableStateOf(false)
        private set
    var isNotificationsEnabled by mutableStateOf(false)
        private set
    var canInstallApps by mutableStateOf(false)
        private set

    val allPermissionsGranted: Boolean
        get() = hasStoragePermission && isNotificationsEnabled && canInstallApps

    init {
        refreshPermissionStates()
    }

    fun refreshPermissionStates() {
        hasStoragePermission = checkStoragePermission()
        isNotificationsEnabled = checkNotificationPermission()
        canInstallApps = checkInstallAppsPermission()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(app).areNotificationsEnabled()
        } else {
            true
        }
    }

    private fun checkInstallAppsPermission(): Boolean {
        return app.packageManager.canRequestPackageInstalls()
    }
}
