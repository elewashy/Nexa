package com.elewashy.nexa.feature.update.domain.model

import java.time.Instant

data class ReleaseHistoryEntry(
    val version: String,
    val description: String,
    /** Null when the release timestamps are malformed (UI hides the date). */
    val createdAt: Instant?
)
