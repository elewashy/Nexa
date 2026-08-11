package com.elewashy.nexa.feature.update.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ManagerUpdateRepository.Companion.compareVersions] — the
 * pure version-ordering logic that decides `hasUpdate`.
 */
class ManagerUpdateRepositoryVersionTest {

    private fun compare(latest: String, current: String): Int =
        ManagerUpdateRepository.compareVersions(latest, current)

    // ── Ordering ────────────────────────────────────────────────────────

    @Test
    fun `newer patch minor and major versions compare greater`() {
        assertTrue(compare("1.2.4", "1.2.3") > 0)
        assertTrue(compare("1.3.0", "1.2.9") > 0)
        assertTrue(compare("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun `older versions compare less`() {
        assertTrue(compare("1.2.3", "1.2.4") < 0)
        assertTrue(compare("1.2.9", "1.3.0") < 0)
        assertTrue(compare("0.9.9", "1.0.0") < 0)
    }

    @Test
    fun `segments compare numerically not lexically`() {
        // "10" > "9" numerically although "10" < "9" lexically.
        assertTrue(compare("1.10.0", "1.9.9") > 0)
        assertTrue(compare("1.2.10", "1.2.9") > 0)
        assertTrue(compare("10.0.0", "9.0.0") > 0)
    }

    // ── Equal ───────────────────────────────────────────────────────────

    @Test
    fun `identical versions are equal`() {
        assertEquals(0, compare("1.2.3", "1.2.3"))
        assertEquals(0, compare("1.2.0", "1.2.0"))
    }

    @Test
    fun `missing trailing segments compare as zero`() {
        assertEquals(0, compare("1.2", "1.2.0"))
        assertEquals(0, compare("1.2.0", "1.2"))
        assertEquals(0, compare("1", "1.0.0.0"))
        assertTrue(compare("1.2.1", "1.2") > 0)
        assertTrue(compare("1.2", "1.2.1") < 0)
    }

    @Test
    fun `leading v prefix is ignored on either side`() {
        assertEquals(0, compare("v1.2.0", "1.2.0"))
        assertEquals(0, compare("1.2.0", "v1.2.0"))
        assertEquals(0, compare("v1.2.0", "v1.2.0"))
        assertTrue(compare("v1.3.0", "v1.2.0") > 0)
    }

    // ── Malformed ───────────────────────────────────────────────────────

    @Test
    fun `non numeric segments compare as zero`() {
        assertEquals(0, compare("1.x.3", "1.0.3"))
        assertEquals(0, compare("abc", "0"))
        // "beta" parses to 0, so "1.0.beta" == "1.0.0".
        assertEquals(0, compare("1.0.beta", "1.0.0"))
        assertTrue(compare("1.beta.1", "1.0.0") > 0)
    }

    @Test
    fun `suffixes inside a segment truncate at the number`() {
        // toIntOrNull on "0-DEBUG" is null → 0, matching how currentVersion
        // is already pre-stripped of its -DEBUG suffix upstream.
        assertEquals(0, compare("1.2.0-DEBUG", "1.2.0"))
    }

    @Test
    fun `empty strings compare as zero`() {
        assertEquals(0, compare("", ""))
        assertEquals(0, compare("", "0.0.0"))
        assertTrue(compare("0.0.1", "") > 0)
    }
}
