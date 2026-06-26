package com.elewashy.nexa.feature.splash.domain.usecase

import android.util.Log
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.links.ValidLinkRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Triggers a parallel refresh of the two network-backed blocklists used by
 * the browser (ad-blocking, valid-links allowlist).
 *
 * Fire-and-forget by design: the splash activity calls [invoke] and then
 * `finish()`s immediately. The work must survive the activity's destruction,
 * so this use case launches on the injected [@ApplicationScope] scope instead
 * of the caller's [androidx.lifecycle.viewModelScope].
 *
 * A [SupervisorJob] isolates the three children — one blocklist failing
 * (e.g. a 500 from the server) must not cancel the other two. Exceptions are
 * logged and swallowed; the app flow continues with whatever cached lists
 * happen to be on disk.
 *
 * Browser resource repositories are Hilt-managed singletons; cached data remains
 * available if a remote refresh fails.
 */
class InitializeBlocklistsUseCase @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val adBlockRepository: AdBlockRepository,
    private val validLinkRepository: ValidLinkRepository,
) {

    operator fun invoke() {
        applicationScope.launch(
            ioDispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Blocklist init error: ${e.message}", e)
            }
        ) {
            val adBlockerUpdate = async {
                try {
                    adBlockRepository.refreshDueAdBlockLists()
                } catch (e: Exception) {
                    Log.e(TAG, "AdBlocker update failed: ${e.message}", e)
                }
            }
            val validLinksUpdate = async {
                try {
                    validLinkRepository.updateValidLinks()
                } catch (e: Exception) {
                    Log.e(TAG, "ValidLinkRepository update failed: ${e.message}", e)
                }
            }

            adBlockerUpdate.await()
            validLinksUpdate.await()

            Log.d(TAG, "Blocklists initialized successfully")
        }
    }

    private companion object {
        const val TAG = "InitBlocklistsUseCase"
    }
}
