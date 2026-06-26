package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.VideoExtractor

internal interface PlatformVideoExtractor {
    suspend fun extract(url: String): VideoExtractor.ExtractionResult
}
