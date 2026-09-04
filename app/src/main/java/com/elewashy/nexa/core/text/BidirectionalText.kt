package com.elewashy.nexa.core.text

import java.text.Bidi

/**
 * Resolves the paragraph direction from the first strong directional
 * character, matching the Unicode Bidirectional Algorithm. Neutral prefixes
 * such as numbers and punctuation do not force an otherwise Arabic or Hebrew
 * title into left-to-right layout.
 */
fun String.hasRtlBaseDirection(): Boolean {
    if (isBlank()) return false
    return !Bidi(this, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
}

/** Mirrors a directional affordance to follow user-provided content rather than app locale. */
fun String.contentDirectionScaleX(): Float = if (hasRtlBaseDirection()) -1f else 1f
