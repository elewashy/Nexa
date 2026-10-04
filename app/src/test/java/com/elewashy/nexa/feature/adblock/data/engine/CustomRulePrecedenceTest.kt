package com.elewashy.nexa.feature.adblock.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom rules are compiled into the same engine as the filter lists, so
 * the single uBO precedence applies across both: exceptions beat blocks,
 * `$important` beats exceptions, `$badfilter` disables list rules.
 */
class CustomRulePrecedenceTest {

    private fun engine(list: List<String>, custom: List<String>, trustCustom: Boolean = false): FilterEngine {
        val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
        builder.addList(list.joinToString("\n").reader().buffered(), trusted = true, sourceKey = "list")
        builder.addRules(custom, trustCustom, sourceKey = "custom")
        return builder.build()
    }

    private fun FilterEngine.blocks(url: String, page: String = "https://site.com/"): Boolean =
        matchRequest(url, RequestTypeResolver.resolve(url, null, false).primary, pageContext(page)).shouldBlock

    @Test
    fun `custom block rule blocks what no list blocks`() {
        val e = engine(list = emptyList(), custom = listOf("||tracker.example^"))
        assertTrue(e.blocks("https://tracker.example/p.js"))
        assertFalse(e.blocks("https://cdn.example/p.js"))
    }

    @Test
    fun `custom exception overrides a list block`() {
        val e = engine(list = listOf("||cdn.example^"), custom = listOf("@@||cdn.example/player.js"))
        assertFalse(e.blocks("https://cdn.example/player.js"))
        assertTrue(e.blocks("https://cdn.example/ad.js"))
    }

    @Test
    fun `custom important block wins over a list exception`() {
        val e = engine(list = listOf("@@||ads.example^"), custom = listOf("||ads.example^\$important"))
        assertTrue(e.blocks("https://ads.example/a.js"))
    }

    @Test
    fun `custom badfilter disables a list rule`() {
        val e = engine(list = listOf("||ads.example^\$script"), custom = listOf("||ads.example^\$script,badfilter"))
        assertFalse(e.blocks("https://ads.example/a.js"))
    }

    @Test
    fun `document exception in custom rules allowlists the whole page`() {
        val e = engine(list = listOf("||ads.example^"), custom = listOf("@@||site.com^\$document"))
        assertFalse(e.blocks("https://ads.example/a.js", page = "https://site.com/"))
        assertTrue(e.blocks("https://ads.example/a.js", page = "https://other.com/"))
    }

    @Test
    fun `rule counts are reported per source`() {
        val e = engine(list = listOf("||a.example^", "||b.example^"), custom = listOf("||c.example^", "! comment"))
        assertEquals(2, e.sourceRuleCounts["list"])
        assertEquals(1, e.sourceRuleCounts["custom"])
    }

    @Test
    fun `a custom rule identical to a list rule is not counted twice`() {
        val e = engine(list = listOf("||a.example^"), custom = listOf("||a.example^"))
        assertEquals(0, e.sourceRuleCounts["custom"] ?: 0)
        assertTrue(e.blocks("https://a.example/x.js"))
    }
}
