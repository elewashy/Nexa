package com.elewashy.nexa.feature.browser.domain.usecase

import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.browser.domain.model.HomePage
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Resolves the address the browser opens for Home and new tabs: the user's custom home page, or
 * the selected search engine's home page.
 */
class ResolveHomePageUseCase @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    /** The user's home page choice; re-emits when it changes. */
    val homePage: Flow<HomePage> = appPreferences.homePageUrl
        .map(HomePage::fromStoredValue)
        .distinctUntilChanged()

    /** The home page address; re-emits when the home page or the search engine changes. */
    val homePageUrl: Flow<String> = combine(
        homePage,
        appPreferences.selectedSearchEngine.map(SearchEngine::fromStoredValue),
    ) { homePage, engine -> homePage.urlFor(engine) }
        .distinctUntilChanged()

    /** The current home page address, read from storage. */
    suspend operator fun invoke(): String = homePageUrl.first()
}
