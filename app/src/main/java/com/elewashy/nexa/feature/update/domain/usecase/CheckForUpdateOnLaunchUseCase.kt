package com.elewashy.nexa.feature.update.domain.usecase

import android.util.Log
import com.elewashy.nexa.core.network.NetworkMonitor
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The automatic update check of an app launch. Off the startup path: it never blocks the UI,
 * waits for a validated network instead of failing offline, and does nothing when automatic
 * checks are disabled in Settings. The result is published by [ManagerUpdateRepository].
 */
class CheckForUpdateOnLaunchUseCase @Inject constructor(
    private val appPreferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val managerUpdateRepository: ManagerUpdateRepository,
) {
    suspend operator fun invoke() {
        if (!appPreferences.autoUpdateCheck.first()) return
        networkMonitor.online.first { it }
        try {
            managerUpdateRepository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the Updates page offers a manual check with the error.
            Log.w(TAG, "Automatic update check failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "UpdateCheck"
    }
}
