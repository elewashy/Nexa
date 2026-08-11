package com.elewashy.nexa.feature.browser.data.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure EasyList host-rule parsing and host matching in
 * [AdBlockRepository.Companion] (`||` prefix handling, lowercasing,
 * parent-walk TLD stop).
 *
 * Intentional production behavior under test here:
 *  - The domain part of a rule terminates at the FIRST of `^ / ? $ # :`, so
 *    paths, ports and options never leak into the host set.
 *  - A leading `www.` is stripped at parse time, matching the way checked
 *    hosts are normalized — `||www.tracker.net^` registers `tracker.net`.
 */
class AdBlockHostMatchingTest {

    private fun parse(vararg lines: String): Set<String> {
        val hosts = HashSet<String>()
        lines.forEach { AdBlockRepository.addHostRule(it, hosts) }
        return hosts
    }

    // ── Rule parsing ────────────────────────────────────────────────────

    @Test
    fun `domain rule with caret is added`() {
        assertEquals(setOf("example.com"), parse("||example.com^"))
    }

    @Test
    fun `rule options after the caret are stripped`() {
        assertEquals(setOf("example.com"), parse("||example.com^\$third-party"))
    }

    @Test
    fun `rule without caret is added whole`() {
        assertEquals(setOf("example.com"), parse("||example.com"))
    }

    @Test
    fun `rules are normalised to lowercase`() {
        assertEquals(setOf("doubleclick.net"), parse("||DoubleClick.NET^"))
    }

    @Test
    fun `leading www is stripped at parse time`() {
        // Hosts are checked lowercase and www-stripped, so rules must be
        // stored the same way or they would never match.
        assertEquals(setOf("example.com"), parse("||www.example.com^"))
        assertEquals(setOf("example.com"), parse("||WWW.Example.Com^"))
        // Only a LEADING www is stripped — deeper labels are kept.
        assertEquals(setOf("cdn.www.example.com"), parse("||cdn.www.example.com^"))
    }

    @Test
    fun `path rules register only the domain`() {
        assertEquals(setOf("x.com"), parse("||x.com/path"))
        assertEquals(setOf("x.com"), parse("||x.com/path/to/script.js"))
    }

    @Test
    fun `option rules register only the domain`() {
        assertEquals(setOf("x.com"), parse("||x.com\$third-party"))
    }

    @Test
    fun `domain terminates at the first structural char`() {
        assertEquals(setOf("x.com"), parse("||x.com?q=1"))
        assertEquals(setOf("x.com"), parse("||x.com#anchor"))
        assertEquals(setOf("x.com"), parse("||x.com:8080"))
        // Whichever terminator comes first wins.
        assertEquals(setOf("x.com"), parse("||x.com/path?query\$option"))
    }

    @Test
    fun `www prefixed rule blocks the apex domain end to end`() {
        val hosts = parse("||www.tracker.net^")
        assertEquals(setOf("tracker.net"), hosts)
        assertTrue(AdBlockRepository.isAdHost("tracker.net", hosts))
        assertTrue(AdBlockRepository.isAdHost("www.tracker.net", hosts))
        assertTrue(AdBlockRepository.isAdHost("cdn.www.tracker.net", hosts))
    }

    @Test
    fun `comma lines are skipped`() {
        // Cosmetic/option-bearing lines carry commas and are not host rules.
        assertTrue(parse("||a.com,important").isEmpty())
        assertTrue(parse("example.com##div,span").isEmpty())
    }

    @Test
    fun `comments are skipped`() {
        assertTrue(parse("! this is a comment").isEmpty())
        assertTrue(parse("![Adblock Plus 2.0]").isEmpty())
    }

    @Test
    fun `exception rules are skipped`() {
        assertTrue(parse("@@||example.com^").isEmpty())
    }

    @Test
    fun `blank and malformed lines are ignored`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("||").isEmpty())
        assertTrue(parse("||   ^").isEmpty())
        assertTrue(parse("example.com").isEmpty()) // missing || prefix
        assertTrue(parse("||example.com^", "", "not a rule").let { it == setOf("example.com") })
    }

    // ── Host matching ───────────────────────────────────────────────────

    @Test
    fun `exact host match hits`() {
        val hosts = setOf("ads.example.com")
        assertTrue(AdBlockRepository.isAdHost("ads.example.com", hosts))
    }

    @Test
    fun `subdomain matches via parent walk`() {
        val hosts = setOf("example.com")
        assertTrue(AdBlockRepository.isAdHost("ads.example.com", hosts))
        assertTrue(AdBlockRepository.isAdHost("ads.tracker.example.com", hosts))
    }

    @Test
    fun `intermediate parent rule matches deeper hosts`() {
        val hosts = setOf("tracker.example.com")
        assertTrue(AdBlockRepository.isAdHost("ads.tracker.example.com", hosts))
        assertFalse(AdBlockRepository.isAdHost("example.com", hosts))
    }

    @Test
    fun `walk stops before bare TLD`() {
        // Even if a bare TLD ends up in the set it must never match.
        val hosts = setOf("com")
        assertFalse(AdBlockRepository.isAdHost("example.com", hosts))
        assertFalse(AdBlockRepository.isAdHost("a.b.example.com", hosts))
    }

    @Test
    fun `literal www entry in the host set still matches deeper hosts`() {
        // New rules are www-stripped at parse time, but host sets persisted by
        // older versions may still carry literal www-prefixed entries.
        val hosts = setOf("www.example.com")
        assertTrue(AdBlockRepository.isAdHost("www.example.com", hosts))
        assertTrue(AdBlockRepository.isAdHost("cdn.www.example.com", hosts))
        // The walk only goes up — a www entry never blocks the apex.
        assertFalse(AdBlockRepository.isAdHost("example.com", hosts))
    }

    @Test
    fun `suffix lookalikes do not match`() {
        val hosts = setOf("example.com")
        assertFalse(AdBlockRepository.isAdHost("notexample.com", hosts))
        assertFalse(AdBlockRepository.isAdHost("badexample.com", hosts))
    }

    @Test
    fun `single label and unmatched hosts miss`() {
        val hosts = setOf("example.com")
        assertFalse(AdBlockRepository.isAdHost("localhost", hosts))
        assertFalse(AdBlockRepository.isAdHost("other.net", hosts))
        assertFalse(AdBlockRepository.isAdHost("anything", emptySet()))
    }

    @Test
    fun `parsing and matching work end to end`() {
        val hosts = parse(
            "! header comment",
            "@@||allowed.example.com^",
            "||EXAMPLE.com^",
            "||www.tracker.net^",
            "||cdn.served.com/banner.js",
            "||cosmetic.com,div#banner",
            ""
        )
        assertEquals(setOf("example.com", "tracker.net", "cdn.served.com"), hosts)
        assertTrue(AdBlockRepository.isAdHost("example.com", hosts))
        assertTrue(AdBlockRepository.isAdHost("a.b.example.com", hosts))
        assertTrue(AdBlockRepository.isAdHost("cdn.www.tracker.net", hosts))
        // www is stripped at parse time, so the apex itself is blocked now.
        assertTrue(AdBlockRepository.isAdHost("tracker.net", hosts))
        // Path rules register the bare domain only.
        assertTrue(AdBlockRepository.isAdHost("cdn.served.com", hosts))
        // Exception rules are dropped at parse time — they grant no exemption,
        // so this host is still blocked through the parent rule's walk.
        assertTrue(AdBlockRepository.isAdHost("allowed.example.com", hosts))
        assertFalse(AdBlockRepository.isAdHost("cosmetic.com", hosts))
    }
}
