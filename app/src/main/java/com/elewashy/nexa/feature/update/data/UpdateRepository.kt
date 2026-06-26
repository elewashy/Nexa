package com.elewashy.nexa.feature.update.data

import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo

interface UpdateRepository {
    suspend fun getLatestRelease(includePrereleases: Boolean = false): ReleaseInfo
    suspend fun getReleases(includePrereleases: Boolean = false): List<ReleaseHistoryEntry>
}
