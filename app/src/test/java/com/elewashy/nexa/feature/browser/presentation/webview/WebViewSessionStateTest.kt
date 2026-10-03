package com.elewashy.nexa.feature.browser.presentation.webview

import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewSessionStateTest {

    @Test
    fun `round trips every supported value type`() {
        val engineState = ByteArray(4096) { (it % 251).toByte() }
        val bundle = Bundle().apply {
            putByteArray("WEBVIEW_CHROMIUM_STATE", engineState)
            putString("label", "ünïcödé")
            putInt("int", 42)
            putLong("long", Long.MAX_VALUE)
            putBoolean("flag", true)
        }

        val decoded = WebViewSessionState.decode(WebViewSessionState.encode(bundle)!!)!!

        assertEquals(bundle.keySet(), decoded.keySet())
        assertArrayEquals(engineState, decoded.getByteArray("WEBVIEW_CHROMIUM_STATE"))
        assertEquals("ünïcödé", decoded.getString("label"))
        assertEquals(42, decoded.getInt("int"))
        assertEquals(Long.MAX_VALUE, decoded.getLong("long"))
        assertTrue(decoded.getBoolean("flag"))
    }

    @Test
    fun `refuses to encode an incomplete snapshot`() {
        assertNull(WebViewSessionState.encode(Bundle()))
        val unsupported = Bundle().apply {
            putByteArray("state", byteArrayOf(1))
            putBundle("nested", Bundle())
        }
        assertNull(WebViewSessionState.encode(unsupported))
    }

    @Test
    fun `rejects malformed input instead of throwing`() {
        val valid = WebViewSessionState.encode(Bundle().apply { putByteArray("s", byteArrayOf(1, 2, 3)) })!!
        assertNotNull(WebViewSessionState.decode(valid))

        assertNull(WebViewSessionState.decode(ByteArray(0)))
        assertNull(WebViewSessionState.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(WebViewSessionState.decode(valid.copyOf(valid.size - 1)))
        assertNull(WebViewSessionState.decode(valid + byteArrayOf(0)))
        // Corrupted length prefix pointing far beyond the buffer.
        val corrupted = valid.copyOf().also { it[12] = 0x7F }
        assertNull(WebViewSessionState.decode(corrupted))
    }
}
