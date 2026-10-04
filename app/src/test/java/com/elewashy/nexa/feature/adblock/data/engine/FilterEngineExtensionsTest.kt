package com.elewashy.nexa.feature.adblock.data.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `$removeparam`, `$csp`, generic exceptions/scriptlets, `site>>`, regex hostnames and new operators. */
class FilterEngineExtensionsTest {

    private fun engine(vararg lines: String, trusted: Boolean = true): FilterEngine {
        val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
        builder.addList(lines.joinToString("\n").reader().buffered(), trusted)
        return builder.build()
    }

    private fun unsupported(vararg lines: String): List<String> {
        val skipped = ArrayList<String>()
        FilterEngine.Builder(HeuristicRegistrableDomainResolver)
            .addList(lines.joinToString("\n").reader().buffered(), true) { skipped += it }
        return skipped
    }

    private fun FilterEngine.payload(frame: String, top: String? = null) = JSONObject(cosmeticPayload(frame, top))

    private fun JSONObject.scriptletNames(): List<String> {
        val calls = getJSONArray("scriptlets")
        return (0 until calls.length()).map { calls.getJSONArray(it).getString(0) }
    }

    private fun JSONObject.csp(): List<String> {
        val list = optJSONArray("csp") ?: return emptyList()
        return (0 until list.length()).map { list.getString(it) }
    }

    // ── $removeparam ────────────────────────────────────────────────────

    @Test
    fun `removeparam strips matching parameters from page loads`() {
        val e = engine(
            "\$removeparam=utm_source",
            "\$removeparam=/^fbclid=/",
            "||shop.com^\$removeparam=ref",
            "||clean.com^\$removeparam",
            "||keep.com^\$removeparam=~id",
        )
        assertEquals(
            "https://news.com/a?id=1#top",
            e.removeParams("https://news.com/a?utm_source=x&id=1&fbclid=abc#top"),
        )
        assertEquals("https://shop.com/p?x=1", e.removeParams("https://shop.com/p?ref=mail&x=1"))
        assertEquals("https://news.com/p?ref=mail", e.removeParams("https://news.com/p?ref=mail&utm_source=a"))
        assertEquals("https://clean.com/p", e.removeParams("https://clean.com/p?a=1&b=2"))
        assertEquals("https://keep.com/p?id=7", e.removeParams("https://keep.com/p?a=1&id=7&b=2"))
        assertNull("nothing to strip", e.removeParams("https://news.com/a?id=1"))
        assertNull("no query", e.removeParams("https://news.com/a"))
        // Removeparam filters never block.
        assertFalse(e.matchRequest("https://news.com/a?utm_source=x", RequestType.SCRIPT, e.pageContext("https://news.com/")).shouldBlock)
    }

    @Test
    fun `removeparam exceptions cancel one or all specs and document allowlisting wins`() {
        val e = engine(
            "\$removeparam=utm_source",
            "\$removeparam=gclid",
            "@@||partner.com^\$removeparam=utm_source",
            "@@||trusted.com^\$removeparam",
            "@@||allowed.com^\$document",
        )
        assertEquals("https://partner.com/?utm_source=a", e.removeParams("https://partner.com/?utm_source=a&gclid=b"))
        assertNull(e.removeParams("https://trusted.com/?utm_source=a&gclid=b"))
        assertNull(e.removeParams("https://allowed.com/?utm_source=a"))
    }

    @Test
    fun `removeparam that cannot target documents is reported unsupported`() {
        assertEquals(
            listOf("\$xhr,removeparam=utm_source", "\$removeparam=/(/"),
            unsupported("\$xhr,removeparam=utm_source", "\$removeparam=/(/", "\$document,removeparam=x"),
        )
    }

    // ── $csp ────────────────────────────────────────────────────────────

    @Test
    fun `csp filters add enforceable policies to pages and frames`() {
        val e = engine(
            "||site.com^\$csp=script-src 'self'",
            "\$csp=worker-src 'none',domain=site.com",
            "||sandboxed.com^\$csp=sandbox allow-scripts; frame-src 'none'",
            "||frames.com^\$csp=child-src 'none',subdocument",
        )
        assertEquals(listOf("script-src 'self'", "worker-src 'none'"), e.payload("https://www.site.com/").csp())
        // `sandbox` is ignored by meta policies and dropped.
        assertEquals(listOf("frame-src 'none'"), e.payload("https://sandboxed.com/").csp())
        // Frames: matched as subdocuments of the top page.
        assertEquals(listOf("child-src 'none'"), e.payload("https://frames.com/embed", "https://host.com/").csp())
        assertTrue(e.payload("https://frames.com/").csp().isEmpty())
        assertTrue(e.payload("https://unrelated.com/").csp().isEmpty())
        // Modifier filters never block.
        assertFalse(e.matchDocument("https://www.site.com/").shouldBlock)
    }

    @Test
    fun `csp exceptions cancel one or all policies and csp-only sandbox is unsupported`() {
        val e = engine(
            "||site.com^\$csp=script-src 'self'",
            "||site.com^\$csp=worker-src 'none'",
            "@@||site.com/app^\$csp=worker-src 'none'",
            "@@||site.com/free^\$csp",
            "@@||allowed.site.com^\$document",
        )
        assertEquals(listOf("script-src 'self'"), e.payload("https://site.com/app").csp())
        assertTrue(e.payload("https://site.com/free").csp().isEmpty())
        assertTrue(e.payload("https://allowed.site.com/").csp().isEmpty())
        assertEquals(listOf("||x.com^\$csp=sandbox", "||x.com^\$csp"), unsupported("||x.com^\$csp=sandbox", "||x.com^\$csp"))
    }

    // ── Cosmetic exceptions & generic scriptlets ────────────────────────

    @Test
    fun `generic exceptions cancel matching site-specific filters`() {
        val e = engine(
            "site.com##.ad-box",
            "site.com##.keep",
            "#@#.ad-box",
            "site.com##+js(set, a, 1)",
            "site.com##+js(set, b, 1)",
            "#@#+js(set, a, 1)",
        )
        val payload = e.payload("https://site.com/")
        assertFalse(payload.getString("css").contains(".ad-box"))
        assertTrue(payload.getString("css").contains(".keep{"))
        assertEquals(listOf("b"), e.scriptletsForHost("site.com").map { it.args[0] })
    }

    @Test
    fun `generic scriptlets apply everywhere except excluded sites`() {
        val e = engine("*,~safe.com##+js(prevent-clipboard-write, /curl .+\\| bash/)", "~other.com##+js(alert-buster)")
        assertEquals(listOf("prevent-clipboard-write", "alert-buster"), e.payload("https://news.com/").scriptletNames())
        assertEquals(listOf("alert-buster"), e.payload("https://www.safe.com/").scriptletNames())
        assertEquals(listOf("prevent-clipboard-write"), e.payload("https://other.com/").scriptletNames())
    }

    @Test
    fun `exceptions for unimplemented scriptlets are accepted`() {
        assertTrue(unsupported("site.com#@#+js(google-ima)", "site.com#@#+js(some-future-scriptlet, x)").isEmpty())
        assertEquals(listOf("site.com##+js(some-future-scriptlet, x)"), unsupported("site.com##+js(some-future-scriptlet, x)"))
    }

    @Test
    fun `ancestor entries apply to the site and every frame it embeds`() {
        val e = engine("embedder.com>>##+js(nowoif)", "plain.com,embedder.com>>##.overlay")
        assertEquals(listOf("no-window-open-if"), e.payload("https://embedder.com/").scriptletNames())
        assertEquals(listOf("no-window-open-if"), e.payload("https://player.cdn.net/e/1", "https://www.embedder.com/").scriptletNames())
        assertTrue(e.payload("https://player.cdn.net/e/1", "https://elsewhere.com/").scriptletNames().isEmpty())
        assertTrue(e.payload("https://plain.com/").getString("css").contains(".overlay{"))
        assertFalse(e.payload("https://cdn.net/", "https://plain.com/").getString("css").contains(".overlay{"))
        assertTrue(e.payload("https://cdn.net/", "https://embedder.com/").getString("css").contains(".overlay{"))
    }

    @Test
    fun `regex hostname entries match their hosts`() {
        val e = engine(
            "fixed.com,/^moon-[a-z0-9]+\\.(?:com|xyz)\$/,/^[0-9a-z]{5,8}\\.(art|sbs)\$/##+js(acs, open)",
            "/^www\\.sports[a-z0-9-]+\\.xyz\$/##.banner",
        )
        for (host in listOf("fixed.com", "moon-abc1.com", "qwert12.sbs")) {
            assertEquals(host, listOf("abort-current-script"), e.payload("https://$host/").scriptletNames())
        }
        assertTrue(e.payload("https://moon.com/").scriptletNames().isEmpty())
        assertTrue(e.payload("https://www.sportshd12.xyz/").getString("css").contains(".banner{"))
        assertFalse(e.payload("https://www.other.xyz/").getString("css").contains(".banner{"))
    }

    @Test
    fun `new procedural operators are compiled as procedural filters`() {
        val e = engine(
            "site.com##.player:others()",
            "site.com##.host:shadow(.ad)",
            "site.com##div:matches-prop(dataset.ad)",
            "site.com##.box:has-text(Ad):watch-attr(class, data-state)",
        )
        val procedural = e.payload("https://site.com/").getJSONArray("procedural")
        assertEquals(4, procedural.length())
        assertTrue(unsupported("site.com##.x:spath(> .y)").isNotEmpty())
    }

    // ── Redirects ───────────────────────────────────────────────────────

    @Test
    fun `redirect-rule none outranks lower priority redirects`() {
        val e = engine(
            "||ads.com^\$script",
            "||ads.com^\$script,redirect-rule=noopjs",
            "||ads.com^\$script,redirect-rule=none:10,domain=plain.com",
        )
        val elsewhere = e.matchRequest("https://ads.com/a.js", RequestType.SCRIPT, e.pageContext("https://news.com/"))
        assertEquals(MatchResult.Decision.REDIRECT, elsewhere.decision)
        val plain = e.matchRequest("https://ads.com/a.js", RequestType.SCRIPT, e.pageContext("https://plain.com/"))
        assertEquals(MatchResult.Decision.BLOCK, plain.decision)
    }

    @Test
    fun `new redirect resources and aliases resolve`() {
        for (name in listOf(
            "noop-0.5s.mp3", "adthrive_abd.js", "addthis_widget.js", "amazon_ads.js", "ampproject_v0.js",
            "doubleclick_instream_ad_status.js", "fingerprint3.js", "google-analytics_cx_api.js",
            "google-analytics_inpage_linkid.js", "hd-main.js", "nitropay_ads.js", "popads-dummy.js",
            "sensors-analytics.js",
        )) {
            assertEquals(name, RedirectResources.canonicalName(name))
        }
        assertEquals("amazon_ads.js", RedirectResources.canonicalName("amazon-adsystem.com/aax2/amzn_ads.js"))
        assertEquals("google-analytics_cx_api.js", RedirectResources.canonicalName("google-analytics.com/cx/api.js"))
    }
}
