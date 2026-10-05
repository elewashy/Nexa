package com.elewashy.nexa.feature.onboarding.domain.usecase

import android.util.Log
import com.elewashy.nexa.core.storage.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/**
 * Whether the first-launch onboarding has to be shown: true until the user finishes or skips it
 * once, then false for every later launch, configuration change and process recreation. Only
 * clearing the app's data (or reinstalling) brings it back.
 *
 * A preferences file that cannot be read never traps the user in onboarding: the app opens
 * normally instead (a corrupt file is already replaced by the DataStore corruption handler).
 */
class ShouldShowOnboardingUseCase @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    operator fun invoke(): Flow<Boolean> = appPreferences.onboardingCompleted
        .map { completed -> !completed }
        .distinctUntilChanged()
        .catch { error ->
            if (error !is IOException) throw error
            Log.w(TAG, "Onboarding state unreadable; opening the browser", error)
            emit(false)
        }

    private companion object {
        const val TAG = "Onboarding"
    }
}
