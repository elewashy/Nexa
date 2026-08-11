package com.elewashy.nexa.feature.update.domain

import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry

/**
 * Fetches the release changelog list.
 *
 * The GitHub releases endpoint returns the full (per_page-capped) list in a
 * single request, so this is a plain suspend fetch — no paging machinery.
 */
class ChangelogsRepository(
    private val updateRepository: UpdateRepository,
    private val includePrereleases: Boolean
) {

    suspend fun getReleases(): List<ReleaseHistoryEntry> =
        updateRepository.getReleases(includePrereleases)
}
