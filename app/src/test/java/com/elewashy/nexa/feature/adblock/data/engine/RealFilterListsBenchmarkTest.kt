package com.elewashy.nexa.feature.adblock.data.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Compiles the real default filter lists and checks behaviour and speed.
 * Opt-in (network-free CI): point `NEXA_FILTER_LISTS_DIR` at a directory
 * containing the downloaded lists.
 */
class RealFilterListsBenchmarkTest {

    private val dir = System.getenv("NEXA_FILTER_LISTS_DIR")?.let(::File)

    private fun compile(): FilterEngine {
        val files = dir!!.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
        val start = System.nanoTime()
        for (f in files) {
            val stats = f.bufferedReader().use { builder.addList(it, trusted = !f.name.startsWith("easy")) }
            println("${f.name}: $stats")
        }
        val engine = builder.build()
        println("compile: ${(System.nanoTime() - start) / 1_000_000} ms, ${engine.stats}")
        return engine
    }

    @Test
    fun `real lists block ads without breaking common sites`() {
        assumeTrue(dir?.isDirectory == true)
        val engine = compile()

        fun decide(url: String, page: String, type: Int = RequestTypeResolver.resolve(url, null, false).primary) =
            engine.matchRequest(url, type, engine.pageContext(page))

        val shouldBlock = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js" to "https://news.example/",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js" to "https://news.example/",
            "https://www.google-analytics.com/analytics.js" to "https://news.example/",
            "https://static.doubleclick.net/instream/ad_status.js" to "https://www.youtube.com/",
            "https://c.amazon-adsystem.com/aax2/apstag.js" to "https://news.example/",
            "https://ads.pubmatic.com/AdServer/js/pwt/123/pwt.js" to "https://news.example/",
            "https://cdn.taboola.com/libtrc/site/loader.js" to "https://news.example/",
        )
        for ((url, page) in shouldBlock) {
            val result = decide(url, page)
            println("BLOCK? $url -> $result")
            assertTrue("$url should be blocked: $result", result.shouldBlock)
        }

        val shouldPass = listOf(
            Triple("https://www.google.com/search?q=test", "https://www.google.com/", RequestType.SUBDOCUMENT),
            Triple("https://www.gstatic.com/og/_/js/k=og.qtm.en_US.js", "https://www.google.com/", RequestType.SCRIPT),
            Triple("https://fonts.googleapis.com/css2?family=Roboto", "https://news.example/", RequestType.STYLESHEET),
            // fetch()/XHR of an extension-less URL: Accept `*/*`, type unknown.
            Triple("https://fonts.googleapis.com/css2?family=Lato", "https://www.speedtest.net/", RequestType.XHR),
            Triple("https://api.github.com/repos/x/y", "https://github.com/", RequestType.XHR),
            Triple("https://rr2---sn-uxaxjvhxbt2u-j5pld.googlevideo.com/videoplayback?expire=1&id=o-AN&itag=243", "https://m.youtube.com/watch?v=x", RequestType.XHR),
            Triple("https://fonts.gstatic.com/s/roboto/v30/x.woff2", "https://news.example/", RequestType.FONT),
            Triple("https://www.google.com/recaptcha/api.js", "https://shop.example/", RequestType.SCRIPT),
            Triple("https://cdn.jsdelivr.net/npm/jquery@3/dist/jquery.min.js", "https://shop.example/", RequestType.SCRIPT),
            Triple("https://cdnjs.cloudflare.com/ajax/libs/vue/3.0.0/vue.min.js", "https://shop.example/", RequestType.SCRIPT),
            Triple("https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", "https://en.wikipedia.org/", RequestType.IMAGE),
            Triple("https://github.githubassets.com/assets/app.js", "https://github.com/", RequestType.SCRIPT),
            Triple("https://i.ytimg.com/vi/abc/hqdefault.jpg", "https://www.youtube.com/", RequestType.IMAGE),
            Triple("https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js", "https://www.youtube.com/", RequestType.SCRIPT),
            Triple("https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1", "https://www.youtube.com/", RequestType.MEDIA),
            Triple("https://pbs.twimg.com/media/abc.jpg", "https://x.com/", RequestType.IMAGE),
            Triple("https://static.xx.fbcdn.net/rsrc.php/v3/x.js", "https://www.facebook.com/", RequestType.SCRIPT),
            Triple("https://ajax.googleapis.com/ajax/libs/jquery/3.6.0/jquery.min.js", "https://shop.example/", RequestType.SCRIPT),
            Triple("https://js.stripe.com/v3/", "https://shop.example/", RequestType.SCRIPT),
            Triple("https://www.paypal.com/sdk/js?client-id=x", "https://shop.example/", RequestType.SCRIPT),
            Triple("https://challenges.cloudflare.com/turnstile/v0/api.js", "https://shop.example/", RequestType.SCRIPT),
        )
        for ((url, page, type) in shouldPass) {
            val result = decide(url, page, type)
            println("PASS? $url -> $result")
            assertFalse("$url must not be blocked: $result", result.shouldBlock)
        }

        for (doc in listOf(
            "https://www.google.com/", "https://www.youtube.com/watch?v=x", "https://github.com/",
            "https://en.wikipedia.org/wiki/Main_Page", "https://www.amazon.com/", "https://www.reddit.com/",
            "https://mail.google.com/", "https://www.bbc.com/news", "https://www.aljazeera.net/",
        )) {
            assertFalse("$doc document must load", engine.matchDocument(doc).shouldBlock)
        }
        assertTrue(engine.matchDocument("https://doubleclick.net/").shouldBlock)
        assertEquals(MatchResult.Decision.NO_MATCH, decide("https://example.org/app.js", "https://example.org/").decision)

        // Throughput over a mixed corpus.
        val corpus = (shouldBlock.map { Triple(it.first, it.second, RequestType.SCRIPT) } + shouldPass).let { base ->
            (0 until 2000).map { i -> base[i % base.size].let { Triple(it.first + "&n=$i", it.second, it.third) } }
        }
        val pages = corpus.map { it.second }.distinct().associateWith { engine.pageContext(it) }
        repeat(3) { corpus.forEach { engine.matchRequest(it.first, it.third, pages.getValue(it.second)) } }
        val start = System.nanoTime()
        val rounds = 10
        repeat(rounds) { corpus.forEach { engine.matchRequest(it.first, it.third, pages.getValue(it.second)) } }
        val perRequestUs = (System.nanoTime() - start) / 1000.0 / (rounds * corpus.size)
        println("match: %.2f µs/request".format(perRequestUs))
        assertTrue("matching too slow: $perRequestUs µs", perRequestUs < 200)

        val cosmeticStart = System.nanoTime()
        val payload = engine.cosmeticPayload("https://www.youtube.com/watch?v=x", null)
        println("cosmetic payload: ${payload.length} chars in ${(System.nanoTime() - cosmeticStart) / 1000} µs")
        val news = engine.cosmeticPayload("https://news.example/", null)
        println("generic page payload: ${news.length} chars; youtube options=${engine.pageContext("https://www.youtube.com/").pageOptions}")
        assertTrue(news.contains("[ad-unit"))
        println("youtube scriptlets: ${engine.scriptletsForHost("www.youtube.com").size}")
        println("generic css sample: ${engine.genericCss("https://news.example/", null, listOf(".ad-banner", "#ad_unit", ".adsbygoogle")).take(200)}")
    }
}
