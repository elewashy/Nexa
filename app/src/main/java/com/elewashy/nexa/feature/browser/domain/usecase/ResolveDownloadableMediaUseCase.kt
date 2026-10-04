package com.elewashy.nexa.feature.browser.domain.usecase

import com.elewashy.nexa.feature.browser.domain.model.PageMediaProbeResult
import com.elewashy.nexa.feature.share.domain.model.MediaPage
import com.elewashy.nexa.feature.share.domain.model.MediaPresence
import javax.inject.Inject

/**
 * Decides whether a classified page offers downloadable media, i.e. whether
 * the browser's download button may be shown for it.
 *
 * - No [MediaPage] (feeds, profiles, search, unsupported sites): never.
 * - Page types that always carry media (YouTube videos, Instagram posts and
 *   reels, TikTok videos and photo posts, Facebook videos and photos): from
 *   the URL alone.
 * - Page types that may be text-only (tweets, Threads posts, Facebook posts):
 *   the platform's own report decides when there is one ([platformReport],
 *   e.g. X's data for a tweet); otherwise the in-page probe must have
 *   confirmed media for this exact content. A probe report about other
 *   content (stale, or from before an SPA navigation) never counts.
 *
 * Pure and synchronous: evaluated on every URL, probe or report change.
 */
class ResolveDownloadableMediaUseCase @Inject constructor() {

    operator fun invoke(page: MediaPage?, probe: PageMediaProbeResult?, platformReport: Boolean?): Boolean {
        page ?: return false
        return when (page.presence) {
            MediaPresence.GUARANTEED -> true
            MediaPresence.REQUIRES_PAGE_CHECK -> platformReport ?: when (probe) {
                // Without a probe the URL is the best signal available.
                PageMediaProbeResult.Unavailable -> true
                is PageMediaProbeResult.Reported -> probe.hasMedia && probe.contentKey == page.contentKey
                null -> false
            }
        }
    }
}
