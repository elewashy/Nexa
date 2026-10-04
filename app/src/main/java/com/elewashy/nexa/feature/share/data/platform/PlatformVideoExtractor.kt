package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult

/**
 * Extracts downloadable media for one [platform].
 *
 * Implementations are called on an IO dispatcher by `VideoExtractor`, which
 * also maps their exceptions: throw `ExtractionException` for expected
 * failures; `IOException`s are reported as network failures.
 */
internal interface PlatformVideoExtractor {
    val platform: SharePlatform
    suspend fun extract(url: String): ExtractionResult
}
