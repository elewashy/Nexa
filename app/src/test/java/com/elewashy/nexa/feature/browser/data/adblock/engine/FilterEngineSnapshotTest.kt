package com.elewashy.nexa.feature.browser.data.adblock.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class FilterEngineSnapshotTest {

    private val lines = listOf(
        "||ads.example^",
        "||tracker.net^\$third-party",
        "/banner/*\$image,domain=news.com|~sport.news.com",
        "@@||ads.example/allowed.js\$script",
        "||cdn.ads^\$important",
        "||cdn.ads/ok.js\$script,important,badfilter",
        "||gpt.example/gpt.js\$script,redirect=googletagservices_gpt.js",
        "||ads.com^\$script,redirect-rule=noopjs",
        "@@||allowed.com^\$document",
        "@@||nojs.com^\$jsinject",
        "\$removeparam=utm_source",
        "@@||partner.com^\$removeparam",
        "||site.com^\$csp=script-src 'self'",
        "/\\/ad[sx]?\\/[0-9]+\\.js/\$script",
        "0.0.0.0 hosts-file.example",
        "##.ad-banner",
        "###sponsor",
        "##div[data-ad]",
        "~safe.com##.negated-generic",
        "site.com##.promo",
        "site.com#@#.promo-ok",
        "#@#.ad-banner-exempt",
        "site.com##.post:has-text(Sponsored)",
        "site.com##body:style(overflow: auto !important)",
        "site.com##+js(set, ads.enabled, false)",
        "site.com##+js(trusted-replace-fetch-response, '\"adSlots\"', '\"no_ads\"', player?)",
        "embedder.com>>##+js(nowoif)",
        "/^moon-[a-z]+\\.com\$/##+js(acs, open)",
        "*,~safe.com##+js(alert-buster)",
        "off.site.com#@#+js()",
    )

    private fun compile(): FilterEngine {
        val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
        builder.addList(lines.joinToString("\n").reader().buffered(), trusted = true)
        return builder.build()
    }

    private fun roundTrip(engine: FilterEngine, key: String = KEY): FilterEngine? {
        val bytes = ByteArrayOutputStream().also { FilterEngineSnapshot.write(engine, KEY, it) }.toByteArray()
        return FilterEngineSnapshot.read(ByteArrayInputStream(bytes), key, HeuristicRegistrableDomainResolver)
    }

    @Test
    fun `restored engine decides exactly like the compiled one`() {
        val original = compile()
        val restored = roundTrip(original)!!
        assertEquals(original.stats, restored.stats)
        assertSameBehaviour(original, restored, REQUESTS, PAGES)
    }

    @Test
    fun `snapshot for another key or format is ignored and corruption is detected`() {
        val engine = compile()
        assertNull(roundTrip(engine, key = "other-build"))
        assertNull(FilterEngineSnapshot.read(ByteArrayInputStream(ByteArray(16)), KEY, HeuristicRegistrableDomainResolver))
        val bytes = ByteArrayOutputStream().also { FilterEngineSnapshot.write(engine, KEY, it) }.toByteArray()
        try {
            FilterEngineSnapshot.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)), KEY, HeuristicRegistrableDomainResolver)
            fail("truncated snapshot must not load")
        } catch (_: IOException) {
            // expected
        }
    }

    /** Opt-in: real lists (`NEXA_FILTER_LISTS_DIR`) — equivalence and restore time vs compile time. */
    @Test
    fun `real lists restore faster than they compile and behave identically`() {
        val dir = System.getenv("NEXA_FILTER_LISTS_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        val files = dir!!.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        val compileStart = System.nanoTime()
        val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
        for (f in files) f.bufferedReader().use { builder.addList(it, trusted = !f.name.startsWith("easy")) }
        val original = builder.build()
        val compileMs = (System.nanoTime() - compileStart) / 1_000_000

        val bytes = ByteArrayOutputStream().also { FilterEngineSnapshot.write(original, KEY, it) }.toByteArray()
        // Warm up, then time the restore.
        FilterEngineSnapshot.read(ByteArrayInputStream(bytes), KEY, HeuristicRegistrableDomainResolver)
        val restoreStart = System.nanoTime()
        val restored = FilterEngineSnapshot.read(ByteArrayInputStream(bytes), KEY, HeuristicRegistrableDomainResolver)
        val restoreMs = (System.nanoTime() - restoreStart) / 1_000_000
        println("compile: $compileMs ms, snapshot: ${bytes.size / 1024} KB, restore: $restoreMs ms")
        assertNotNull(restored)
        assertEquals(original.stats, restored!!.stats)
        assertTrue("restore ($restoreMs ms) should beat compile ($compileMs ms)", restoreMs < compileMs)
        assertSameBehaviour(
            original, restored,
            REQUESTS + listOf(
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js" to RequestType.SCRIPT,
                "https://www.google-analytics.com/analytics.js" to RequestType.SCRIPT,
                "https://static.doubleclick.net/instream/ad_status.js" to RequestType.SCRIPT,
                "https://i.ytimg.com/vi/abc/hqdefault.jpg" to RequestType.IMAGE,
            ),
            PAGES + listOf("https://www.youtube.com/watch?v=x", "https://m.youtube.com/", "https://streamwish.to/e/x"),
        )
    }

    private fun assertSameBehaviour(
        a: FilterEngine,
        b: FilterEngine,
        requests: List<Pair<String, Int>>,
        pages: List<String>,
    ) {
        for (page in pages) {
            val ctxA = a.pageContext(page)
            val ctxB = b.pageContext(page)
            assertEquals(page, ctxA.pageOptions, ctxB.pageOptions)
            for ((url, type) in requests) {
                assertEquals("$url on $page", a.matchRequest(url, type, ctxA).toString(), b.matchRequest(url, type, ctxB).toString())
            }
            assertEquals(page, a.matchDocument(page).toString(), b.matchDocument(page).toString())
            assertEquals(page, a.removeParams("$page?utm_source=x&id=1"), b.removeParams("$page?utm_source=x&id=1"))
            assertEquals(page, a.cosmeticPayload(page, null), b.cosmeticPayload(page, null))
            assertEquals(page, a.cosmeticPayload("https://frame.cdn.net/e/1", page), b.cosmeticPayload("https://frame.cdn.net/e/1", page))
            assertEquals(page, a.genericCss(page, null, TOKENS), b.genericCss(page, null, TOKENS))
        }
    }

    private companion object {
        const val KEY = "build-1|lists-1"
        val TOKENS = listOf(".ad-banner", "#sponsor", ".ad-banner-exempt", ".unknown")
        val PAGES = listOf(
            "https://site.com/", "https://news.com/", "https://sport.news.com/", "https://allowed.com/",
            "https://nojs.com/", "https://partner.com/", "https://off.site.com/", "https://embedder.com/",
            "https://moon-abc.com/", "https://safe.com/",
        )
        val REQUESTS = listOf(
            "https://ads.example/a.js" to RequestType.SCRIPT,
            "https://ads.example/allowed.js" to RequestType.SCRIPT,
            "https://tracker.net/p.gif" to RequestType.IMAGE,
            "https://cdn.news.com/banner/x.png" to RequestType.IMAGE,
            "https://cdn.ads/ok.js" to RequestType.SCRIPT,
            "https://gpt.example/gpt.js" to RequestType.SCRIPT,
            "https://ads.com/x.js" to RequestType.SCRIPT,
            "https://cdn.example/ads/123.js" to RequestType.SCRIPT,
            "https://hosts-file.example/x" to RequestType.XHR,
            "https://unrelated.org/app.js" to RequestType.SCRIPT,
        )
    }
}
