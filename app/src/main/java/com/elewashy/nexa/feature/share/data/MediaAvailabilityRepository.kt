package com.elewashy.nexa.feature.share.data

import com.elewashy.nexa.feature.share.domain.model.MediaPage
import com.elewashy.nexa.feature.share.domain.model.MediaPresence

/**
 * Answers "does this content page have downloadable media?" from the
 * platform's own data, for page types whose URL alone cannot tell
 * ([MediaPresence.REQUIRES_PAGE_CHECK]).
 *
 * Main-safe; results are cached and shared with extraction, so a later
 * download of the same content does not repeat the lookup.
 */
interface MediaAvailabilityRepository {

    /**
     * True or false when the platform reports it; null when it cannot be
     * determined (no data source for the platform, content unavailable to
     * logged-out clients, or the platform unreachable).
     */
    suspend fun hasDownloadableMedia(page: MediaPage): Boolean?
}
