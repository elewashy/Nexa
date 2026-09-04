package com.elewashy.nexa.core.text

/**
 * Truncates user input to [maxCodePoints] without splitting a surrogate pair,
 * so emoji and other supplementary-plane characters stay intact at the cut.
 */
fun String.limitCodePoints(maxCodePoints: Int): String {
    require(maxCodePoints >= 0) { "maxCodePoints must be non-negative" }
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}
