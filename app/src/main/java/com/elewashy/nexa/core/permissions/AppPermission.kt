package com.elewashy.nexa.core.permissions

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** A permission the app can ask for up front (onboarding); each is also requested in context. */
enum class AppPermission {
    /** Write access to Downloads/Nexa. See [StorageAccess]. */
    Storage,

    /** Download progress and completion notifications (a runtime permission on Android 13+). */
    Notifications,

    /** Installing downloaded APKs and app updates ("Install unknown apps"). */
    InstallPackages;

    /** Current grant state. Cheap synchronous checks, safe on the main thread. */
    fun isGranted(context: Context): Boolean = when (this) {
        Storage -> StorageAccess.isGranted(context)
        Notifications -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        InstallPackages -> context.packageManager.canRequestPackageInstalls()
    }

    companion object {
        /**
         * The permissions worth asking for on this device, in display order. Notifications only
         * need asking for on Android 13+; before that they are on unless the user turned them off.
         */
        val requestable: List<AppPermission> = buildList {
            add(Storage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Notifications)
            add(InstallPackages)
        }
    }
}
