package com.elewashy.nexa.feature.share.data

/**
 * Signals an expected extraction failure with a user-presentable message.
 * Caught at the extraction boundary and converted to a failed result.
 */
class ExtractionException(message: String) : Exception(message)
