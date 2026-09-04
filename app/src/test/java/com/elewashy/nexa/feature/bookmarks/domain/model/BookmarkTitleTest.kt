package com.elewashy.nexa.feature.bookmarks.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookmarkTitleTest {

    @Test
    fun `title limit counts Unicode code points without splitting emoji`() {
        val title = "😀".repeat(MAX_BOOKMARK_TITLE_CODE_POINTS + 1)

        val limited = title.limitBookmarkTitle()

        assertEquals(MAX_BOOKMARK_TITLE_CODE_POINTS, limited.codePointCount(0, limited.length))
        assertFalse(limited.last().isHighSurrogate())
    }

    @Test
    fun `short mixed-direction title is unchanged`() {
        val title = "مسلسل Reacher - Episode 5"
        assertEquals(title, title.limitBookmarkTitle())
    }
}
