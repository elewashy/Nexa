package com.elewashy.nexa.core.common

import kotlinx.coroutines.CoroutineScope
import javax.inject.Qualifier

/**
 * Qualifier for a [CoroutineScope] tied to the application's lifetime.
 *
 * Use this for fire-and-forget work that must survive an Activity / ViewModel
 * being destroyed (e.g. pushing telemetry, finalising a download).
 *
 * **Do not** use it for UI state; prefer `viewModelScope` or `lifecycleScope`.
 *
 * Replaces misuse of `GlobalScope` in the previous architecture (notably in
 * `SplashActivity` for blocklist updates that had to outlive the Activity).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
