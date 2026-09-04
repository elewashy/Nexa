package com.elewashy.nexa.feature.bookmarks.domain.model

/** User-editable bookmark and folder title limit, measured in Unicode code points. */
const val MAX_BOOKMARK_TITLE_CODE_POINTS = 100

/** Limits text without cutting a UTF-16 surrogate pair (for example an emoji) in half. */
fun String.limitBookmarkTitle(): String {
    if (codePointCount(0, length) <= MAX_BOOKMARK_TITLE_CODE_POINTS) return this
    return substring(0, offsetByCodePoints(0, MAX_BOOKMARK_TITLE_CODE_POINTS))
}
