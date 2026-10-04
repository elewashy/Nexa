package com.elewashy.nexa.feature.adblock.data.engine

import com.elewashy.nexa.feature.adblock.data.engine.FilterRuleSyntax.Intent
import com.elewashy.nexa.feature.adblock.domain.model.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterRuleSyntaxTest {

    @Test
    fun `plain input becomes filter syntax`() {
        assertEquals("||ads.example.com^", FilterRuleSyntax.normalize("ads.example.com", Intent.Block))
        assertEquals("||ads.example.com^", FilterRuleSyntax.normalize("https://ads.example.com/", Intent.Block))
        assertEquals("||ads.example.com/x.js?a=1", FilterRuleSyntax.normalize("https://ads.example.com:443/x.js?a=1", Intent.Block))
        assertNull(FilterRuleSyntax.normalize("   ", Intent.Block))
    }

    @Test
    fun `allow intent turns rules into exceptions`() {
        assertEquals("@@||cdn.example^", FilterRuleSyntax.normalize("cdn.example", Intent.Allow))
        assertEquals("example.com#@#.banner", FilterRuleSyntax.normalize("example.com##.banner", Intent.Allow))
        assertEquals("@@||already.example^", FilterRuleSyntax.normalize("@@||already.example^", Intent.Allow))
        assertEquals("! note", FilterRuleSyntax.normalize("! note", Intent.Allow))
    }

    @Test
    fun `rules are classified with the engine parsers`() {
        assertEquals(RuleKind.Block, FilterRuleSyntax.classify("||ads.example^", trusted = false))
        assertEquals(RuleKind.ImportantBlock, FilterRuleSyntax.classify("||ads.example^\$important", trusted = false))
        assertEquals(RuleKind.Allow, FilterRuleSyntax.classify("@@||ads.example^", trusted = false))
        assertEquals(RuleKind.ElementHiding, FilterRuleSyntax.classify("example.com##.ad", trusted = false))
        assertEquals(RuleKind.ElementHidingException, FilterRuleSyntax.classify("example.com#@#.ad", trusted = false))
        assertEquals(RuleKind.BadFilter, FilterRuleSyntax.classify("||ads.example^\$badfilter", trusted = false))
        assertEquals(RuleKind.Comment, FilterRuleSyntax.classify("! a comment", trusted = false))
        assertEquals(RuleKind.Block, FilterRuleSyntax.classify("0.0.0.0 ads.example", trusted = false))
    }

    @Test
    fun `trusted-only scriptlets need trust`() {
        val rule = "example.com##+js(trusted-set-cookie, a, b)"
        assertEquals(RuleKind.RequiresTrust, FilterRuleSyntax.classify(rule, trusted = false))
        assertEquals(RuleKind.Scriptlet, FilterRuleSyntax.classify(rule, trusted = true))
    }

    @Test
    fun `overlong rules are unsupported`() {
        val rule = "||" + "a".repeat(FilterRuleSyntax.MAX_RULE_LENGTH) + ".example^"
        assertEquals(RuleKind.Unsupported, FilterRuleSyntax.classify(rule, trusted = false))
    }
}
