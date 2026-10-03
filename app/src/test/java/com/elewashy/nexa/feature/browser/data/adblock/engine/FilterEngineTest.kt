package com.elewashy.nexa.feature.browser.data.adblock.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    private fun engine(vararg lines: String, trusted: Boolean = true): FilterEngine {
        val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
        builder.addList(lines.joinToString("\n").reader().buffered(), trusted)
        return builder.build()
    }

    private fun FilterEngine.request(
        url: String,
        page: String = "https://site.com/",
        type: Int = RequestTypeResolver.resolve(url, null, false).primary,
        method: String = "GET",
        alternatives: Int = 0,
    ): MatchResult = matchRequest(url, type, pageContext(page), method, alternatives)

    private fun FilterEngine.blocks(url: String, page: String = "https://site.com/", type: Int? = null): Boolean =
        (if (type == null) request(url, page) else request(url, page, type)).shouldBlock

    // ── Hostname anchoring ──────────────────────────────────────────────

    @Test
    fun `hostname filter matches host and subdomains only at label boundaries`() {
        val e = engine("||ads.example.com^")
        assertTrue(e.blocks("https://ads.example.com/a.js"))
        assertTrue(e.blocks("https://x.ads.example.com/a.js"))
        assertFalse(e.blocks("https://notads.example.com/a.js"))
        assertFalse(e.blocks("https://example.com/a.js"))
        assertFalse(e.blocks("https://ads.example.com.evil.net/a.js"))
    }

    @Test
    fun `path in hostname filter limits the match to that path`() {
        val e = engine("||example.com/ads/")
        assertTrue(e.blocks("https://example.com/ads/banner.png"))
        assertFalse(e.blocks("https://example.com/news/article.html", type = RequestType.SUBDOCUMENT))
        assertFalse(e.blocks("https://example.com/"))
    }

    @Test
    fun `hosts file and plain hostname lines become hostname filters`() {
        val e = engine("0.0.0.0 tracker.net", "127.0.0.1 localhost", "pixel.org")
        assertTrue(e.blocks("https://cdn.tracker.net/p.gif"))
        assertTrue(e.blocks("https://pixel.org/x"))
        assertFalse(e.blocks("http://localhost/x"))
    }

    // ── Options ─────────────────────────────────────────────────────────

    @Test
    fun `third-party option ignores first-party requests`() {
        val e = engine("||widgets.com^\$third-party")
        assertTrue(e.blocks("https://widgets.com/w.js", page = "https://site.com/"))
        assertFalse(e.blocks("https://widgets.com/w.js", page = "https://widgets.com/"))
        assertFalse(e.blocks("https://cdn.widgets.com/w.js", page = "https://www.widgets.com/"))
    }

    @Test
    fun `type options restrict the filter to the given resource types`() {
        val e = engine("||cdn.com^\$script")
        assertTrue(e.blocks("https://cdn.com/lib.js"))
        assertFalse(e.blocks("https://cdn.com/logo.png"))
        assertFalse(e.blocks("https://cdn.com/style.css"))
        val negated = engine("||cdn.com^\$~image")
        assertFalse(negated.blocks("https://cdn.com/logo.png"))
        assertTrue(negated.blocks("https://cdn.com/lib.js"))
    }

    @Test
    fun `domain option supports exclusions and entities`() {
        val e = engine("/sponsor.js\$domain=news.com|~sport.news.com", "||ads.net^\$domain=shop.*")
        assertTrue(e.blocks("https://x.com/sponsor.js", page = "https://news.com/"))
        assertTrue(e.blocks("https://x.com/sponsor.js", page = "https://www.news.com/"))
        assertFalse(e.blocks("https://x.com/sponsor.js", page = "https://sport.news.com/"))
        assertFalse(e.blocks("https://x.com/sponsor.js", page = "https://other.com/"))
        assertTrue(e.blocks("https://ads.net/a.js", page = "https://shop.co.uk/"))
        assertTrue(e.blocks("https://ads.net/a.js", page = "https://shop.de/"))
        assertFalse(e.blocks("https://ads.net/a.js", page = "https://myshop.de/"))
    }

    @Test
    fun `denyallow skips listed request hosts`() {
        val e = engine("*\$script,3p,denyallow=cdnjs.com|jsdelivr.net,domain=pirate.to")
        assertTrue(e.blocks("https://evil.com/pop.js", page = "https://pirate.to/"))
        assertFalse(e.blocks("https://cdnjs.com/jquery.js", page = "https://pirate.to/"))
        assertFalse(e.blocks("https://evil.com/pop.js", page = "https://other.to/"))
    }

    @Test
    fun `method option matches only listed methods`() {
        val e = engine("||api.com/track\$method=post")
        assertTrue(e.request("https://api.com/track", method = "POST").shouldBlock)
        assertFalse(e.request("https://api.com/track", method = "GET").shouldBlock)
    }

    @Test
    fun `match-case is honoured`() {
        val e = engine("/AdServe/\$match-case")
        assertTrue(e.blocks("https://x.com/AdServe/1.js"))
        assertFalse(e.blocks("https://x.com/adserve/1.js"))
    }

    @Test
    fun `filters with unsupported options are skipped instead of over-blocking`() {
        val e = engine(
            "||site.com^\$csp=script-src 'none'",
            "||site.com^\$removeparam=utm_source",
            "||site.com^\$unknownoption",
        )
        assertFalse(e.blocks("https://site.com/app.js"))
    }

    @Test
    fun `regex filters match and tokens are extracted safely`() {
        val e = engine("/^https?:\\/\\/[a-z]{8,15}\\.com\\/ad\\/[0-9]+\\.js/")
        assertTrue(e.blocks("https://abcdefghij.com/ad/123.js"))
        assertFalse(e.blocks("https://abc.com/ad/123.js"))
    }

    @Test
    fun `separator caret matches end of url and separators but not letters`() {
        val e = engine("/banner^")
        assertTrue(e.blocks("https://x.com/banner"))
        assertTrue(e.blocks("https://x.com/banner?x=1"))
        assertFalse(e.blocks("https://x.com/bannerx"))
    }

    // ── Precedence ──────────────────────────────────────────────────────

    @Test
    fun `exceptions override blocks`() {
        val e = engine("||cdn.com^", "@@||cdn.com/jquery.js")
        assertTrue(e.blocks("https://cdn.com/ads.js"))
        val allowed = e.request("https://cdn.com/jquery.js")
        assertEquals(MatchResult.Decision.ALLOW, allowed.decision)
    }

    @Test
    fun `important overrides regular exceptions but not important exceptions`() {
        val e = engine("||ads.com^\$important", "@@||ads.com^")
        assertTrue(e.blocks("https://ads.com/x.js"))
        val e2 = engine("||ads.com^\$important", "@@||ads.com^\$important")
        assertFalse(e2.blocks("https://ads.com/x.js"))
    }

    @Test
    fun `document exception allowlists the whole page including important blocks`() {
        val e = engine("||ads.com^\$important", "/banner.", "@@||trusted.org^\$document")
        assertFalse(e.blocks("https://ads.com/x.js", page = "https://trusted.org/"))
        assertFalse(e.blocks("https://cdn.com/banner.png", page = "https://trusted.org/path"))
        assertTrue(e.blocks("https://cdn.com/banner.png", page = "https://elsewhere.org/"))
    }

    @Test
    fun `genericblock disables generic network filters only`() {
        val e = engine("/generic-ad.", "/specific-ad.\$domain=site.com", "@@||site.com^\$genericblock")
        assertFalse(e.blocks("https://cdn.com/generic-ad.js", page = "https://site.com/"))
        assertTrue(e.blocks("https://cdn.com/specific-ad.js", page = "https://site.com/"))
    }

    @Test
    fun `badfilter removes the matching filter`() {
        val e = engine("||cdn.com^\$third-party", "||cdn.com^\$third-party,badfilter", "||other.com^", "||other.com^\$badfilter")
        assertFalse(e.blocks("https://cdn.com/x.js"))
        assertFalse(e.blocks("https://other.com/x.js"))
    }

    @Test
    fun `decisions are deterministic regardless of evaluation order`() {
        val lines = arrayOf("||a.com^", "@@||a.com/ok^", "||a.com/ok/bad^\$important")
        val e1 = engine(*lines)
        val e2 = engine(*lines.reversedArray())
        for (url in listOf("https://a.com/x", "https://a.com/ok", "https://a.com/ok/bad")) {
            assertEquals(e1.request(url).decision, e2.request(url).decision)
        }
        assertTrue(e1.blocks("https://a.com/ok/bad"))
        assertFalse(e1.blocks("https://a.com/ok"))
    }

    // ── Documents & popups ──────────────────────────────────────────────

    @Test
    fun `pure hostname filters strictly block documents but path filters do not`() {
        val e = engine("||malware.com^", "||news.com/ads/")
        assertTrue(e.matchDocument("https://malware.com/").shouldBlock)
        assertFalse(e.matchDocument("https://news.com/ads/page").shouldBlock)
        assertFalse(e.matchDocument("https://news.com/").shouldBlock)
    }

    @Test
    fun `strict blocking yields to pure hostname exceptions and document filters block`() {
        val e = engine("||cdn.com^", "@@||cdn.com^", "||bad.org^\$doc")
        assertFalse(e.matchDocument("https://cdn.com/").shouldBlock)
        assertTrue(e.matchDocument("https://bad.org/page").shouldBlock)
    }

    @Test
    fun `popup filters apply to cross-site page navigations`() {
        val e = engine("||popads.net^\$popup", "*\$popup,3p,domain=stream.to")
        val opener = e.pageContext("https://stream.to/watch")
        assertTrue(e.matchPopup("https://popads.net/x", opener, userGesture = true).shouldBlock)
        assertFalse(e.matchPopup("https://wikipedia.org/", opener, userGesture = true).shouldBlock)
        assertTrue(e.matchPopup("https://wikipedia.org/", opener, userGesture = false).shouldBlock)
        assertFalse(e.matchPopup("https://stream.to/next", opener, userGesture = false).shouldBlock)
        // Popup-only filters never block regular subresources.
        assertFalse(e.blocks("https://popads.net/x.js", page = "https://stream.to/"))
    }

    // ── Redirects ───────────────────────────────────────────────────────

    @Test
    fun `redirect filters block with a neutered resource`() {
        val e = engine("||googletagmanager.com/gtm.js\$script,redirect=googletagmanager_gtm.js")
        val result = e.request("https://www.googletagmanager.com/gtm.js?id=1")
        assertEquals(MatchResult.Decision.REDIRECT, result.decision)
        assertEquals("googletagmanager_gtm.js", result.redirect)
    }

    @Test
    fun `redirect-rule applies only to requests blocked elsewhere and can be excepted`() {
        val e = engine("||ads.com/ad.js\$script,redirect-rule=noopjs", "||ads.com^")
        assertEquals("noop.js", e.request("https://ads.com/ad.js").redirect)
        val notBlocked = engine("||ads.com/ad.js\$script,redirect-rule=noopjs")
        assertFalse(notBlocked.blocks("https://ads.com/ad.js"))
        val excepted = engine("||ads.com/ad.js\$script,redirect-rule=noopjs", "||ads.com^", "@@||ads.com^\$redirect-rule")
        assertEquals(MatchResult.Decision.BLOCK, excepted.request("https://ads.com/ad.js").decision)
    }

    @Test
    fun `unknown redirect resources discard the filter`() {
        val e = engine("||player.com/ima.js\$script,redirect=does-not-exist.js")
        assertFalse(e.blocks("https://player.com/ima.js"))
    }

    // ── Preprocessor ────────────────────────────────────────────────────

    @Test
    fun `preprocessor directives follow the uBO mobile Chromium environment`() {
        val e = engine(
            "!#if env_safari", "||safari-only.com^", "!#else", "||not-safari.com^", "!#endif",
            "!#if !cap_html_filtering", "||no-html.com^", "!#endif",
            "!#if env_mobile && !env_firefox", "||mobile.com^", "!#endif",
        )
        assertFalse(e.blocks("https://safari-only.com/x"))
        assertTrue(e.blocks("https://not-safari.com/x"))
        assertTrue(e.blocks("https://no-html.com/x"))
        assertTrue(e.blocks("https://mobile.com/x"))
    }

    // ── Cosmetic ────────────────────────────────────────────────────────

    @Test
    fun `specific cosmetic filters apply to their sites and honour exceptions`() {
        val e = engine("site.com##.promo", "site.com,other.com##.banner", "sub.site.com#@#.banner")
        val payload = e.cosmeticPayload("https://www.site.com/", null)
        assertTrue(payload.contains(".promo{display:none!important}"))
        assertTrue(payload.contains(".banner{display:none!important}"))
        val excepted = e.cosmeticPayload("https://sub.site.com/", null)
        assertFalse(excepted.contains(".banner{"))
        assertFalse(e.cosmeticPayload("https://unrelated.com/", null).contains(".promo{"))
    }

    @Test
    fun `generic cosmetic filters split into low and high generic`() {
        val e = engine("##.ad-slot", "##div[id^=\"div-gpt-ad\"]", "site.com#@#.ad-slot")
        assertTrue(e.cosmeticPayload("https://x.com/", null).contains("div[id^=\\\"div-gpt-ad\\\"]{display:none!important}"))
        assertTrue(e.genericCss("https://x.com/", null, listOf(".ad-slot")).contains(".ad-slot{"))
        assertEquals("", e.genericCss("https://site.com/", null, listOf(".ad-slot")))
        assertEquals("", e.genericCss("https://x.com/", null, listOf(".content")))
    }

    @Test
    fun `generichide and elemhide exceptions disable cosmetics`() {
        val e = engine("##.ad", "##[data-ad]", "gen.com##.own", "@@||gen.com^\$generichide", "@@||none.com^\$elemhide")
        val gen = e.cosmeticPayload("https://gen.com/", null)
        assertTrue(gen.contains(".own{"))
        assertFalse(gen.contains("[data-ad]"))
        assertEquals("", e.genericCss("https://gen.com/", null, listOf(".ad")))
        assertEquals(CosmeticIndex.EMPTY_PAYLOAD, e.cosmeticPayload("https://none.com/", null))
    }

    @Test
    fun `procedural and style filters are classified`() {
        val e = engine(
            "site.com##.post:has-text(Sponsored)",
            "site.com##body:style(overflow: auto !important)",
            "site.com##.x:-abp-has(.y)",
            "site.com#\$#.modal { display: none !important; }",
            "##.generic:has-text(ad)",
        )
        val payload = e.cosmeticPayload("https://site.com/", null)
        assertTrue(payload.contains(".post:has-text(Sponsored)"))
        assertTrue(payload.contains("body{overflow: auto !important}"))
        assertTrue(payload.contains(".x:has(.y){display:none!important}"))
        assertTrue(payload.contains(".modal{display: none !important;}"))
        assertFalse(payload.contains(".generic:has-text"))
    }

    // ── Scriptlets ──────────────────────────────────────────────────────

    @Test
    fun `scriptlets resolve aliases, arguments and exceptions`() {
        val e = engine(
            "site.com##+js(set, ads.enabled, false)",
            "site.com##+js(aopr, adblockDetector)",
            "site.com,other.com##+js(nostif, /ad\\,block/, 100)",
            "sub.site.com#@#+js(aopr, adblockDetector)",
            "off.site.com#@#+js()",
            "site.com#%#//scriptlet('prevent-setInterval', 'check')",
        )
        val calls = e.scriptletsForHost("www.site.com").map { it.name to it.args }
        assertTrue(calls.contains("set-constant" to listOf("ads.enabled", "false")))
        assertTrue(calls.contains("abort-on-property-read" to listOf("adblockDetector")))
        assertTrue(calls.contains("no-setTimeout-if" to listOf("/ad,block/", "100")))
        assertTrue(calls.contains("no-setInterval-if" to listOf("check")))
        assertFalse(e.scriptletsForHost("sub.site.com").any { it.name == "abort-on-property-read" })
        assertTrue(e.scriptletsForHost("off.site.com").isEmpty())
        assertTrue(e.scriptletsForHost("unrelated.com").isEmpty())
    }

    @Test
    fun `trusted scriptlets require a trusted list and jsinject disables scriptlets`() {
        val untrusted = engine("site.com##+js(trusted-set, x, '{\"a\":1}')", trusted = false)
        assertTrue(untrusted.scriptletsForHost("site.com").isEmpty())
        val trusted = engine("site.com##+js(trusted-set, x, '{\"a\":1}')")
        assertEquals(listOf("x", "{\"a\":1}"), trusted.scriptletsForHost("site.com").single().args)
        val disabled = engine("site.com##+js(set, a, 1)", "@@||site.com^\$jsinject")
        assertTrue(disabled.scriptletsForHost("site.com").isEmpty())
    }

    @Test
    fun `content payload carries scriptlets only for matching frames`() {
        val e = engine(
            "site.com##+js(set, ads.enabled, false)",
            "site.com##.promo",
            "@@||allowed.site.com^\$jsinject",
            "@@||trusted.example^\$document",
        )
        val payload = JSONObject(e.cosmeticPayload("https://www.site.com/", null))
        val call = payload.getJSONArray("scriptlets").getJSONArray(0)
        assertEquals("set-constant", call.getString(0))
        assertEquals("ads.enabled", call.getJSONArray(1).getString(0))
        assertEquals("false", call.getJSONArray(1).getString(1))
        assertTrue(payload.getString("css").contains(".promo{display:none!important}"))

        // $jsinject keeps cosmetics but drops scriptlets.
        val noJs = JSONObject(e.cosmeticPayload("https://allowed.site.com/", null))
        assertEquals(0, noJs.getJSONArray("scriptlets").length())
        assertTrue(noJs.getString("css").contains(".promo{"))

        assertEquals(0, JSONObject(e.cosmeticPayload("https://unrelated.com/", null)).getJSONArray("scriptlets").length())
        // A frame inside a $document-allowlisted page gets nothing.
        assertEquals(CosmeticIndex.EMPTY_PAYLOAD, e.cosmeticPayload("https://www.site.com/", "https://trusted.example/"))
    }

    @Test
    fun `cosmetic syntax is not mistaken for network filters`() {
        val e = engine("example.com##.ad", "||example.com/#anchor")
        assertTrue(e.blocks("https://example.com/#anchor"))
        assertFalse(e.blocks("https://example.com/page.js"))
    }

    private fun resolve(
        url: String,
        accept: String? = "*/*",
        range: Boolean = false,
        method: String = "GET",
        origin: Boolean = false,
        contentType: String? = null,
        sameOrigin: Boolean? = false,
    ) = RequestTypeResolver.resolve(url, accept, range, method, origin, contentType, sameOrigin)

    @Test
    fun `request type inference uses accept, extension, range, method and CORS signals`() {
        assertEquals(RequestType.SCRIPT, resolve("https://a.com/x.js?v=1").primary)
        assertEquals(RequestType.IMAGE, resolve("https://a.com/pixel", "image/webp,*/*").primary)
        assertEquals(RequestType.STYLESHEET, resolve("https://a.com/s", "text/css,*/*;q=0.1").primary)
        assertEquals(RequestType.SUBDOCUMENT, resolve("https://a.com/f", "text/html,application/xhtml+xml").primary)
        assertEquals(RequestType.MEDIA, resolve("https://a.com/stream", range = true).primary)
        // Classic cross-origin <script> without extension vs. CORS fetch vs. same-origin fetch.
        assertEquals(RequestType.SCRIPT, resolve("https://cdn.com/loader").primary)
        assertEquals(RequestType.XHR, resolve("https://api.com/v1/x", origin = true).primary)
        assertEquals(RequestType.XHR, resolve("https://site.com/api/x", sameOrigin = true).primary)
        // hls.js fetching a manifest is an XHR; fonts stay fonts in CORS mode.
        assertEquals(RequestType.XHR, resolve("https://cdn.com/v/index.m3u8", origin = true).primary)
        assertEquals(RequestType.FONT, resolve("https://fonts.com/a.woff2", origin = true).primary)
        // Uploads: fetch/XHR POSTs (incl. YouTube's videoplayback) are XHR; only <a ping> is a ping.
        assertEquals(RequestType.XHR, resolve("https://rr1.googlevideo.com/videoplayback?x=1", method = "POST", origin = true).primary)
        assertEquals(RequestType.PING, resolve("https://t.com/ping", method = "POST", contentType = "text/ping").primary)
    }

    @Test
    fun `generic ping filters never block fetches or uploads of another type`() {
        // Regressions: EasyPrivacy's `*$ping,third-party` blocked extension-less
        // fetches (Google Fonts CSS) and YouTube's POST videoplayback requests.
        val e = engine("*\$ping,third-party")
        fun blocked(url: String, type: ResolvedType) =
            e.request(url, page = "https://www.youtube.com/", type = type.primary, alternatives = type.alternatives).shouldBlock
        assertFalse(blocked("https://fonts.googleapis.com/css2?family=Roboto", resolve("https://fonts.googleapis.com/css2?family=Roboto", origin = true)))
        val videoplayback = "https://rr2---sn-x.googlevideo.com/videoplayback?expire=1&id=o-AN"
        assertFalse(blocked(videoplayback, resolve(videoplayback, method = "POST", origin = true)))
        assertTrue(blocked("https://t.example/p", resolve("https://t.example/p", method = "POST", contentType = "text/ping")))
    }

    @Test
    fun `alternative types widen exceptions but never blocks`() {
        val e = engine("||t.com/collect\$xhr", "||t.com/a^\$script", "@@||t.com/a^\$xhr")
        val noCors = resolve("https://t.com/collect")
        // Primary SCRIPT: the $xhr block does not apply to an unknown no-cors load…
        assertFalse(e.request("https://t.com/collect", type = noCors.primary, alternatives = noCors.alternatives).shouldBlock)
        // …but does apply when the request is a fetch.
        assertTrue(e.request("https://t.com/collect", type = RequestType.XHR).shouldBlock)
        // A same-origin request may be a fetch, so the $xhr exception covers it…
        val sameOrigin = resolve("https://t.com/a", sameOrigin = true)
        assertEquals(RequestType.XHR, sameOrigin.primary)
        assertFalse(e.request("https://t.com/a", page = "https://t.com/", type = sameOrigin.primary, alternatives = sameOrigin.alternatives).shouldBlock)
        // …but a cross-origin no-cors load is a real <script>: XHR-only exceptions
        // (e.g. `@@||doubleclick.net/|$xmlhttprequest,domain=…`) must not allow it.
        val gpt = "https://securepubads.g.doubleclick.net/tag/js/gpt.js"
        val ex = engine("||doubleclick.net^", "@@||doubleclick.net/|\$xmlhttprequest,domain=tvnz.co.nz")
        val script = resolve(gpt, sameOrigin = false)
        assertTrue(ex.request(gpt, page = "https://www.tvnz.co.nz/", type = script.primary, alternatives = script.alternatives).shouldBlock)
    }
}
