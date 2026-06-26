package com.elewashy.nexa.feature.update.domain.model

import java.time.Instant

data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val createdAt: Instant,
    val fileSize: Long
)
