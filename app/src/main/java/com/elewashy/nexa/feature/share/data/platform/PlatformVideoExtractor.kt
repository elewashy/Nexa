package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult

internal interface PlatformVideoExtractor {
    val platform: SharePlatform
    suspend fun extract(url: String): ExtractionResult
}
