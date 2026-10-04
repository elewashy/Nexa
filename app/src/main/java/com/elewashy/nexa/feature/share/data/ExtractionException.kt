package com.elewashy.nexa.feature.share.data

import com.elewashy.nexa.feature.share.domain.model.ExtractionError

/**
 * Signals an expected extraction failure. Caught at the extraction boundary
 * ([VideoExtractor]) and converted to a failed result carrying [reason].
 */
class ExtractionException(
    message: String,
    val reason: ExtractionError = ExtractionError.NO_MEDIA,
) : Exception(message)
