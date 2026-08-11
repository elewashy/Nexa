package com.elewashy.nexa.feature.update.domain.model

import java.time.Instant

data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String,
    /** Null when the release timestamps are malformed (UI hides the date). */
    val createdAt: Instant?,
    val fileSize: Long,
    /**
     * URL of the release's SHA-256 checksum asset (e.g. `<apk>.sha256`,
     * `checksums.txt`, `SHA256SUMS`), or null when the release publishes none.
     */
    val checksumUrl: String? = null,
)
