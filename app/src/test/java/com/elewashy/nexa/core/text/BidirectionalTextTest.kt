package com.elewashy.nexa.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidirectionalTextTest {

    @Test
    fun `arabic and hebrew titles resolve as rtl`() {
        assertTrue("مسلسل Reacher الموسم الرابع الحلقة 5".hasRtlBaseDirection())
        assertTrue("שלום example".hasRtlBaseDirection())
    }

    @Test
    fun `neutral prefix does not override the first strong rtl character`() {
        assertTrue("5 - مسلسل Reacher".hasRtlBaseDirection())
        assertTrue("... مرحبًا".hasRtlBaseDirection())
    }

    @Test
    fun `latin titles urls and blank text resolve as ltr`() {
        assertFalse("Google بحث".hasRtlBaseDirection())
        assertFalse("https://example.com".hasRtlBaseDirection())
        assertFalse("   ".hasRtlBaseDirection())
    }

    @Test
    fun `directional affordances follow content direction`() {
        assertEquals(-1f, "... مرحبًا".contentDirectionScaleX())
        assertEquals(1f, "https://example.com".contentDirectionScaleX())
    }
}
