package com.elewashy.nexa.feature.onboarding.domain.usecase

import android.util.Log
import com.elewashy.nexa.core.common.BrowserUrls
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.tabs.data.TabRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Finishes (or skips) onboarding: applies the choices that need applying, then records that it
 * is done. The flag is persisted in DataStore, so it holds across restarts and process death;
 * repeating the call is harmless.
 */
class CompleteOnboardingUseCase @Inject constructor(
    private val appPreferences: AppPreferences,
    private val tabRepository: TabRepository,
) {
    suspend operator fun invoke() {
        // Before the flag: the browser is composed as soon as the flag flips, and must find the
        // first tab already pointing at the chosen home page.
        try {
            openChosenHomePageInFirstTab()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: never keep the user in onboarding because of the first tab.
            Log.w(TAG, "Could not apply the chosen home page to the first tab", e)
        }
        appPreferences.setOnboardingCompleted()
    }

    /**
     * The workspace seeds its first tab with the default home page when it is restored, which
     * on the first launch happens while onboarding is on screen — before a search engine is
     * picked. If that tab is still untouched, it opens the chosen engine's home page instead.
     * The browser, and so the tab's WebView, is not created during onboarding, so nothing has
     * loaded in it yet. Any other workspace (an upgrade with real tabs) is left as it is.
     */
    private suspend fun openChosenHomePageInFirstTab() {
        tabRepository.restore()
        val tab = tabRepository.workspace.value.tabs.singleOrNull() ?: return
        val untouched = !tab.isPrivate && tab.url == BrowserUrls.HOME && tab.title.isEmpty()
        if (!untouched || tabRepository.sessionState(tab.id) != null) return

        val homeUrl = SearchEngine.fromStoredValue(appPreferences.selectedSearchEngine.first()).homeUrl
        if (homeUrl == tab.url) return
        tabRepository.urlCommitted(tab.id, homeUrl)
        tabRepository.flushPending()
    }

    private companion object {
        const val TAG = "Onboarding"
    }
}
