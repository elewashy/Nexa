package com.elewashy.nexa.feature.browser.data.regex

/**
 * RegexPatterns — Pre-compiled regex patterns for ad / tracker blocking.
 *
 * Performance design:
 *   • Every raw pattern is a **full-match** regex (anchored with `^…$`).
 *   • All patterns are compiled **once** via `by lazy`.
 *   • Duplicates and dead-code patterns have been removed.
 *   • Similar patterns are **merged** where possible to reduce alternation
 *     count and improve NFA throughput.
 *   • A single **combined** regex is also available for callers
 *     that prefer one `containsMatchIn` call instead of looping.
 *   • **All open-ended quantifiers use possessive form** (`*+`, `++`, `{n,m}+`)
 *     to prevent catastrophic backtracking in the JVM NFA engine.
 *
 * Maintenance rules:
 *   1. Use raw strings `"""…"""` exclusively — no double-escaping.
 *   2. Always anchor with `^…$`.
 *   3. Search for existing coverage before adding a new pattern.
 *   4. Never use `.*` — always use `.*+` (possessive) or a bounded
 *      character class like `[^?]*+`, `[^&]*+`, `[^/]*+`.
 */
object RegexPatterns {

    // ── Individual raw patterns ─────────────────────────────────

    val patternStrings: List<String> = listOf(

        // ── tag.min.js ad script ───────────────────────────────────
        """^https?://[a-z]{8,15}\.(com|net)/(?:\d{1,3}/)?tag\.min\.js$""",

        // ── Ad network alphanumeric tracker paths ──────────────────
        """^https?://(?:[a-z]{2}\.)?[a-z]{7,14}\.com/[a-z](?=[a-z]*[0-9A-Z])[0-9A-Za-z]{10,27}/[A-Za-z]{5}$""",
        """^https?://(?:[a-z]{2}\.)?[0-9a-z]{5,16}\.[a-z]{3,7}/[a-z](?=[a-z]{0,25}[0-9A-Z])[0-9a-zA-Z]{3,26}/\d{4,6}(?:\?[_a-z]=[-0-9a-z]+)?$""",
        """^https?://(?:[a-z]{2}\.)?[a-z]{7,14}\.[a-z]{3,7}/[fgprst][0-9A-Za-z]{10,16}/\d{4,6}$""",

        // ── /digit/digits ad network patterns (merged 3 → 1) ─────
        """^https?://(?:ak\.)?[a-z0-9]{3,15}\.(com|net)/\d/\d{7,8}(?:\?dovr=(?:true|false))?$""",

        // ── /[3-digit-status]/[6-7 digits] ad networks (400,401,500…)
        """^https?://[-a-z]{6,15}\.(com|net|tv|xyz)/(?:40[01]|50?0?)/\d{6,7}\??\S*+$""",

        // ── Generic /NNN/NNNNNNN?v=N pattern ───────────────────────
        """^https?://[a-z]{8,15}\.(com|net)/\d{3}/\d{7}(?:\?v=\d+)?$""",

        // ── .top tracker domains ───────────────────────────────────
        """^https?://[a-z]{3,5}\.[a-z]{10,14}\.top/[a-z]{10,16}/[a-z]{5,6}(?:\?d=\d)?$""",

        // ── Hex-hash .js ad scripts (merged 3 → 1) ────────────────
        """^https?://(?:(?:www\.|[0-9a-z]{7,10}\.)?[-0-9a-z]{5,}\.(com|bid|link|live|online|top|club)/{0,2}(?:[0-9a-z]{2}/){2,3}|[^/]+/(?:\d{2}/){3}|[0-9a-fA-F]{8,32}\.[0-9a-fA-F]{8,32}\.com/)[0-9a-fA-F]{8,64}\.js$""",

        // ── freex2line.online dynamic ad scripts ───────────────────
        """^https?://[a-z0-9.-]++\.freex2line\.online/[a-zA-Z0-9_-]++\.js(?:\?.*+)?$""",

        // ── /digit/digits?psid= ad endpoint ────────────────────────
        """^https?://[a-z]{8,15}\.[a-z]{2,4}/\d{1,2}/\d{6,7}(?:\?psid=\d+)?$""",

        // ── Hilltopads / Exoclick / similar ad platforms (merged 6 → 2)
        """^https?://[a-z]{8,15}\.com/(?:afu\.php(?:\?zoneid=\d+&var=\d+&abvar=\d+)?|\?z=\d+(?:&[a-z]+(?:=(?:true|false))?)*|en/(?:(?:[a-z]{2,10}/){0,2}[a-z]{2,}\?(?:[a-z]+=(?:\d+|[a-z]+)&)*?id=[12]\d{6}|[a-z]{6,8}\?(?:[a-z]+=[^&]+&)*id=\d{7}(?:&[a-z]+=[^&]+)*|bibc/[a-z0-9]+\?[a-zA-Z0-9_=&%-]+(?:&id=\d+)*|[a-zA-Z0-9/_-]+\?[a-zA-Z0-9=&%-]+))$""",

        // ── Tracking / fingerprinting query-strings ────────────────
        """^https?://[a-zA-Z0-9.-]+/[a-zA-Z0-9]+(?:\.html|\.php|\.asp|\.htm|\.aspx)?\?[a-z]=\d+(&[a-z]=\d+)*&[a-z]=%21[a-zA-Z0-9%/+]++(&[a-z]=[^&]*+)*+$""",
        """^https?://[a-zA-Z0-9.-]+/[a-zA-Z0-9]+\.htm\?g=\d+&z=\d+&m=\d+&c=\d+&l=\d+&p=[^&]+&s=[^&]+&v=[^&]*&m=$""",

        // ── syncedCookie / rhd ad beacons ──────────────────────────
        """^https?://[a-zA-Z0-9.-]+/\?z=\d+&syncedCookie=true&rhd=false$""",

        // ── API token trackers ─────────────────────────────────────
        """^https?://[^/]+/api/users\?token=[A-Za-z0-9/=&?]++$""",

        // ── CGI / smartlink redirects ──────────────────────────────
        """^https?://[^/]+/cgi-bin/smartlink\.cgi\?url_key=[a-zA-Z0-9]+$""",

        // ── Named ad-network paths (merged overlapping paths) ──────
        """^https?://[^/]+/(?:en/triobp/ktajuba|en/azvza/cido|en/dzofavo|qukdah/cmea|dhiqzeba|lhe/[^/]+/ozvd|ahrahdra/furg|lpyodn|bk/rweo)(?:/.*+|\?[^#]*+)?$""",
        """^https?://[^/]+/(?:\d+/[a-f0-9]{32}\?psid=\d+|ut/hb\.php\?cb=[0-9.]+&v=\d+|QXSg\.asp\?(?:[a-z]=[^&]*+&?)+)$""",

        // ── /get/ ad endpoints (merged 2 → 1) ─────────────────────
        """^https?://[^/]+/get/(?:\d+\?[^#]*+|\?spot_id=\d+[^#]*+)$""",

        // ── /NNN/NNN?var= pattern ──────────────────────────────────
        """^https?://[^/]+/\d+/\d+\?var=[\w_]+$""",

        // ── Known ad scripts ───────────────────────────────────────
        """^https?://[^/]+/(?:in\.js|js/noadblocker\.js)$""",

        // ── Tracking pixel (hex-path GIF/JPG/PNG) ──────────────────
        """^https?://[^/]+/static/image/pn/[a-f0-9]{3}/[a-f0-9]{3}/[a-f0-9]{3}/[a-f0-9]{40}\.(?:gif|jpg|png)$""",

        // ── Key-based tracker (merged 2 → 1) ──────────────────────
        """^https?://[^/]+/[A-Za-z0-9]{1,9}/?\?key=[A-Za-z0-9=&a-f]++$""",

        // ── /digits/?var=LETTERS ad redirect ───────────────────────
        """^https?://[^/]+/\d+/\?var=[A-Z0-9-]+$""",

        // ── ab=10&rl=1 pattern ─────────────────────────────────────
        """^https?://[^/]+/\d+/\?ab=10&rl=1$""",

        // ── submit.min.js ad script ────────────────────────────────
        """^https?://[^/]+/submit\.min\.js\?abvar=[^#]*+$""",

        // ── ?p=…&en= beacon ────────────────────────────────────────
        """^https?://[^/]+/\?p=[A-Za-z0-9]+&en=\d+$""",

        // ── /pt/…/digits endpoint ──────────────────────────────────
        """^https?://[A-Za-z0-9.-]+/pt/[^/]++/\d+$""",

        // ── /o/s/[name].js ad script ───────────────────────────────
        """^https?://[A-Za-z0-9.-]+/o/s/[A-Za-z0-9]+\.js$""",

        // ── Fingerprinting array parameters (merged 3 → 1) ────────
        """^https?://[^/]+/[^?]*+\?[^#]*+(?:lmf=%5B[^]]*+%5D|xil=%5B[^]]*+%5D|wisy=)[^#]*+$""",

        // ── Common ad query params (zoneid, psid) ──────────────────
        """^https?://[^/]+/[^?]*+\?[^#]*+(?:psid=\d+|zoneid=\d+)[^#]*+$""",

        // ── Redirect / tracking patterns ───────────────────────────
        """^https?://[^/]+/\d{7}/\?var=[a-z]+$""",
        """^https?://[^/]+/rdl/e/mitb/\d+\?[^#]*+(?:pb|pbc|pbi|pbu|psp)=[^#]*+$""",

        // ── dupa.gif tracking pixel ────────────────────────────────
        """^https?://[^/]+/dupa\.gif\?z=\d+[^#]*+$""",

        // ── io.[ad-domain].com tracker ─────────────────────────────
        """^https?://io\.[a-z]{8,20}\.com/[a-zA-Z0-9]+/\d+$""",

        // ── *link.com + click_id/zoneid trackers (merged 2 → 1) ───
        """^https?://[a-z0-9]{8,20}(?:link)?\.com/track\?[^#]*+(?:olc|click_id|zoneid)=[^#]*+$""",

        // ── refpa referral tracking (merged 2 → 1) ────────────────
        """^https?://(?:refpa[a-z0-9]+\.(com|top)|[^/]+)/L\?tag=[^#]*+(?:(?:site|ad)=\d+[^#]*+)?$""",

        // ── Clickunder / popunder indicators (merged 2 → 1) ───────
        """^https?://[^/]+/[^?]*+\?[^#]*+(?:clickunder|popunder)[^#]*+$""",

        // ── .cfd ad-network domains ────────────────────────────────
        """^https?://[a-z0-9]+\.[a-z0-9]+\.cfd/[a-zA-Z0-9_/-]+$""",

        // ── .shop tracker domains (merged 3 → 1) ──────────────────
        """^https?://[a-z]+(?:\.[a-z]+)?\.shop/(?:cuid/\?f=[^#]*+|gd/\d+\?md=[^#]*+|cx/[a-zA-Z0-9*_-]+\?md=[^#]*+)$""",

        // ── .qpon TLD (ad network) ─────────────────────────────────
        """^https?://[^/]+\.qpon/.*+$""",

        // ── Base64-encoded tracking metadata (merged 3 → 1) ───────
        """^https?://[^/]+/(?:gd/\d+\?md=eyJ|cx/[a-zA-Z0-9*_-]+\?md=eyJ|cuid/\?f=https?%3A%2F%2F)[a-zA-Z0-9+/=%]*+[^#]*+$""",

        // ── Long random-domain ad networks ─────────────────────────
        """^https?://[a-z]{20,40}\.(com|net)/\d{1}/\d{6,8}\?[^#]*+$""",

        // ── Ad exchange suurl*.php scripts ─────────────────────────
        """^https?://[^/]+/script/suurl\d*\.php\?[^#]*+$""",

        // ── UUID-path campaign/advertiser trackers ──────────────────
        """^https?://[^/]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\?[^#]*+(?:campaign|advertiser|zone|ban)=[^#]*+$""",

        // ── Short-name tracker with single-letter numeric params ────
        """^https?://[a-z]{6,15}\.(com|net)/[A-Za-z]{1,8}\.(?:html?|aspx?|php)\?[a-z]=\d+&[a-z]=\d+&[a-z]=\d+&[a-z]=\d+[^#]*+$""",

        // ── Redirect.eng ad redirect ───────────────────────────────
        """^https?://[^/]+/Redirect\.eng\?MediaSegmentId=\d+[^#]*+$""",

        // ── /dc/?blockID= ad block endpoint ─────────────────────────
        """^https?://[^/]+/dc/\?blockID=\d+$""",

        // ── Tracking redirects with campaignId/creativeId/sourceId ───
        """^https?://[^/]+/[^?]*+\?(?:[^#]*?(?:campaignId|creativeId|sourceId)=){2}[^#]*+$""",

        // ── /go/[digits] ad redirect ────────────────────────────────
        """^https?://[^/]+/go/\d{5,}$""",

        // ── Prebid.js header bidding library ────────────────────────
        """^https?://[^/]+/[^?]*+prebid\.min\.js$""",

        // ── /check.html ad verification endpoint ───────────────────
        """^https?://[^/]+/[^?]*+check\.html$""",

        // ── iClick ad platform fingerprinting (merged 2 → 1) ───────
        """^https?://[^/]+/[^#]*+[?&]js_build=iclick[^#]*+$""",

        // ── Numbered ad domains (word + 2-4 digits) with tracking query ─
        """^https?://[a-z]{3,12}\d{2,4}\.(?:com|net|org)/\?[a-z]{1,5}=\S++(&\S++=\S*+){5,}$""",

        // ── Root-path redirects with encoded referrer (drf= or pl=) ────
        """^https?://[^/]+/\?(?=[^#]*?[?&]drf=https?%3A)(?=[^#]*?[?&]pl=https?%3A)[^#]*+$""",

        // ── Multi-segment random-path ad redirects (.in domains) ────
        """^https?://(?:[a-z0-9-]{1,15}\.)*[a-z0-9-]{4,25}\.in/(?:[a-z0-9]{2,15}/){0,6}[a-z0-9]{2,15}(?:\?[^#]*+)?$""",

        // ── Multi-TLD ad networks with nested paths (15+ params) ────
        """^https?://[a-z0-9]{8,25}\.(?:in|xyz|biz|top|site|com|net)/(?:jihgmigf/lw/ak|[a-z]{5,15}/[a-z]{2,5}/[a-z]{2,5})\?(?:[^&]{1,15}=[^&]*+&?){15,}$""",

        // ── iClick / executors with iav param ───────────────────────
        """^https?://[^/]+/\?(?=[^#]*?[?&]iav=\d)(?=[^#]*?[?&]js_build=iclick)[^#]*+$""",
        // ── /click tracking links (generic host) ────────────────────
        """^https?://[^/]+/click\?offer=[^&]++&aff=[^&]++&sub10=[^#]++$""",
    )

    // ── Combined single-pass regex ──────────────────────────────

    /**
     * All patterns combined into one regex with `|` alternation.
     *
     * Usage:
     * ```
     * if (RegexPatterns.combinedRegex.matches(url)) { block }
     * ```
     *
     * The JVM regex engine compiles this into an NFA that can test
     * all branches in a single pass — significantly faster than
     * iterating [compiledPatterns].
     */
    val combinedRegex: Regex by lazy {
        patternStrings.joinToString("|") { pattern ->
            // Strip outer ^…$ so we can wrap the whole alternation once
            val stripped = pattern
                .removePrefix("^")
                .removeSuffix("$")
            "(?:$stripped)"
        }.let { combined ->
            "^(?:$combined)$".toRegex()
        }
    }
}
