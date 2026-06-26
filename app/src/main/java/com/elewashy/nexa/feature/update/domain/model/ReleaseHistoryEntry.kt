package com.elewashy.nexa.feature.update.domain.model

import java.time.Instant

data class ReleaseHistoryEntry(
    val version: String,
    val description: String,
    val createdAt: Instant
)
