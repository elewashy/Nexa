package com.elewashy.nexa.feature.browser.data.adblock.engine

/**
 * Cosmetic, procedural and scriptlet filters, organised like uBO:
 *  - **specific** filters are indexed by hostname / entity hash and resolved
 *    per host (with `#@#` exceptions applied); `site>>` filters are also
 *    indexed in [ancestorSpecific] so frames embedded in that site find them
 *    through the top document's host; the few filters with `/regex/`
 *    hostnames ([regexSpecific]) are tested against every host;
 *  - **generic exceptions** (`#@#sel`, `#@#+js(…)`) cancel the matching
 *    generic *and* site-specific filters everywhere;
 *  - **low-generic** filters (`##.class…` / `###id…`) are keyed by their
 *    leading class/id and served only when the content script reports that
 *    class/id in the DOM, which keeps the injected stylesheet small;
 *  - **high-generic** filters (everything else) are precompiled into one
 *    stylesheet; one rule per selector so an unsupported selector never
 *    voids its neighbours;
 *  - **generic scriptlets** (`*##+js(…)`, usually with `~site` exclusions)
 *    are few and checked per host.
 */
internal class CosmeticIndex internal constructor(
    internal val specific: SortedLongMap<CosmeticFilter>,
    internal val ancestorSpecific: SortedLongMap<CosmeticFilter>,
    internal val regexSpecific: List<CosmeticFilter>,
    internal val negatedGeneric: List<CosmeticFilter>,
    internal val genericScriptlets: List<CosmeticFilter>,
    internal val genericExceptions: Set<String>,
    internal val lowGeneric: Map<String, List<String>>,
    internal val highGenericSelectors: List<String>,
    val cosmeticCount: Int,
    val scriptletCount: Int,
) {
    val hasScriptlets: Boolean get() = scriptletCount > 0

    private val highGenericCss: String =
        buildString { for (s in highGenericSelectors) append(CosmeticFilterParser.hideRule(s)).append('\n') }

    private val hostCache = LruCache<String, HostCosmetics>(HOST_CACHE_SIZE)
    // Payloads embed the high-generic stylesheet (tens of KB), so keep fewer.
    private val payloadCache = LruCache<String, String>(PAYLOAD_CACHE_SIZE)

    private class HostCosmetics(
        val specificCss: List<String>,
        val procedural: List<String>,
        val negatedGenericCss: List<String>,
        val exceptionKeys: Set<String>,
        val scriptlets: List<ScriptletCall>,
    )

    /**
     * JSON payload for the content script: CSS, procedural selectors, whether
     * the low-generic surveyor should run and, when [includeScriptlets], the
     * scriptlet calls as `[[name, [args…]], …]`. [top] is the top document's
     * host for frames (null for top-level documents).
     */
    fun payload(ctx: HostContext, top: HostContext?, options: Int, includeScriptlets: Boolean): String {
        val cacheKey = cacheKey(ctx, top) + '|' + options + '|' + includeScriptlets
        payloadCache[cacheKey]?.let { return it }
        val resolved = resolve(ctx, top)
        val specificDisabled = options and PageOption.SPECIFICHIDE != 0
        val genericDisabled = options and PageOption.GENERICHIDE != 0

        val css = StringBuilder()
        if (!specificDisabled) resolved.specificCss.forEach { css.append(it).append('\n') }
        if (!genericDisabled) {
            if (resolved.exceptionKeys.isEmpty()) {
                css.append(highGenericCss)
            } else {
                for (selector in highGenericSelectors) {
                    if (selector !in resolved.exceptionKeys) css.append(CosmeticFilterParser.hideRule(selector)).append('\n')
                }
            }
            resolved.negatedGenericCss.forEach { css.append(it).append('\n') }
        }
        val json = buildString {
            append("{\"css\":").append(Json.quote(css.toString()))
            append(",\"procedural\":[")
            if (!specificDisabled) {
                resolved.procedural.forEachIndexed { i, p ->
                    if (i > 0) append(',')
                    append(Json.quote(p))
                }
            }
            append("],\"generic\":").append(!genericDisabled && lowGeneric.isNotEmpty())
            append(",\"scriptlets\":[")
            if (includeScriptlets) {
                resolved.scriptlets.forEachIndexed { i, call ->
                    if (i > 0) append(',')
                    append('[').append(Json.quote(call.name)).append(",[")
                    call.args.forEachIndexed { j, arg ->
                        if (j > 0) append(',')
                        append(Json.quote(arg))
                    }
                    append("]]")
                }
            }
            append("]}")
        }
        payloadCache[cacheKey] = json
        return json
    }

    fun lowGenericCss(ctx: HostContext, top: HostContext?, tokens: List<String>): String {
        if (tokens.isEmpty() || lowGeneric.isEmpty()) return ""
        val exceptions = resolve(ctx, top).exceptionKeys
        val css = StringBuilder()
        for (token in tokens) {
            val selectors = lowGeneric[token] ?: continue
            for (selector in selectors) {
                if (selector !in exceptions) css.append(CosmeticFilterParser.hideRule(selector)).append('\n')
            }
        }
        return css.toString()
    }

    fun scriptlets(ctx: HostContext, top: HostContext? = null): List<ScriptletCall> = resolve(ctx, top).scriptlets

    /** Frames only resolve differently from their own host when `site>>` filters exist. */
    private fun cacheKey(ctx: HostContext, top: HostContext?): String =
        if (top == null || top.host == ctx.host || ancestorSpecific.size == 0) ctx.host else ctx.host + '>' + top.host

    private fun resolve(ctx: HostContext, top: HostContext?): HostCosmetics {
        val key = cacheKey(ctx, top)
        hostCache[key]?.let { return it }
        val frameTop = top?.takeIf { it.host != ctx.host }
        val candidates = LinkedHashSet<CosmeticFilter>()
        forEachHostKey(ctx) { hash -> specific[hash]?.let(candidates::addAll) }
        if (frameTop != null && ancestorSpecific.size != 0) {
            forEachHostKey(frameTop) { hash -> ancestorSpecific[hash]?.let(candidates::addAll) }
        }
        candidates.addAll(regexSpecific)

        // Site-specific exceptions; generic ones were applied to generic filters at build time.
        val exceptionKeys = HashSet<String>()
        var allScriptletsDisabled = CosmeticFilterParser.ALL_SCRIPTLETS_KEY in genericExceptions
        val applicable = ArrayList<CosmeticFilter>(candidates.size)
        for (f in candidates) {
            if (!f.appliesTo(ctx, frameTop)) continue
            if (f.exception) {
                if (f.kind == CosmeticFilter.Kind.SCRIPTLET && f.key == CosmeticFilterParser.ALL_SCRIPTLETS_KEY) {
                    allScriptletsDisabled = true
                }
                exceptionKeys.add(f.key)
            } else {
                applicable.add(f)
            }
        }
        val css = ArrayList<String>()
        val procedural = ArrayList<String>()
        val scriptlets = ArrayList<ScriptletCall>()
        val seenScriptlets = HashSet<String>()
        for (f in applicable) {
            if (f.key in exceptionKeys || f.key in genericExceptions) continue
            when (f.kind) {
                CosmeticFilter.Kind.HIDE, CosmeticFilter.Kind.STYLE -> f.css?.let(css::add)
                CosmeticFilter.Kind.PROCEDURAL -> procedural.add(f.selector)
                CosmeticFilter.Kind.SCRIPTLET -> if (!allScriptletsDisabled && seenScriptlets.add(f.key)) {
                    f.scriptlet?.let(scriptlets::add)
                }
            }
        }
        if (!allScriptletsDisabled) {
            for (f in genericScriptlets) {
                if (f.key in exceptionKeys || f.domains?.matches(ctx) == false || !seenScriptlets.add(f.key)) continue
                f.scriptlet?.let(scriptlets::add)
            }
        }
        val negated = negatedGeneric.filter { it.key !in exceptionKeys && it.domains?.matches(ctx) != false }
            .mapNotNull { it.css }
        val resolved = HostCosmetics(css.distinct(), procedural.distinct(), negated, exceptionKeys, scriptlets)
        hostCache[key] = resolved
        return resolved
    }

    private inline fun forEachHostKey(ctx: HostContext, action: (Long) -> Unit) {
        val host = ctx.host
        if (host.isEmpty()) return
        val entityEnd = if (ctx.publicSuffixLength > 0) host.length - ctx.publicSuffixLength else -1
        var start = 0
        while (true) {
            action(Fnv.hash(host, start, host.length))
            if (start < entityEnd) action(Fnv.entity(host, start, entityEnd))
            val dot = host.indexOf('.', start)
            if (dot == -1) return
            start = dot + 1
        }
    }

    class Builder {
        private val specific = SortedLongMap.Builder<CosmeticFilter>()
        private val ancestorSpecific = SortedLongMap.Builder<CosmeticFilter>()
        private val regexSpecific = ArrayList<CosmeticFilter>()
        private val genericHide = ArrayList<CosmeticFilter>()
        private val genericScriptlets = ArrayList<CosmeticFilter>()
        private val genericExceptions = HashSet<String>()
        private val negatedGeneric = ArrayList<CosmeticFilter>()
        private var cosmeticCount = 0
        private var scriptletCount = 0

        fun add(filter: CosmeticFilter) {
            val scriptlet = filter.kind == CosmeticFilter.Kind.SCRIPTLET
            if (scriptlet) scriptletCount++ else cosmeticCount++
            val domains = filter.domains
            val ancestors = filter.ancestors
            when {
                filter.isGeneric && filter.exception -> genericExceptions.add(filter.key)
                filter.isGeneric -> if (scriptlet) genericScriptlets.add(filter) else genericHide.add(filter)
                filter.isNegatedGeneric -> when {
                    filter.exception -> Unit // `~a.com#@#sel` is meaningless; ignore.
                    scriptlet -> genericScriptlets.add(filter)
                    else -> negatedGeneric.add(filter)
                }
                else -> {
                    if (domains != null && domains.hasIncludes) for (hash in domains.includeHashes()) specific.add(hash, filter)
                    if (ancestors != null) {
                        for (hash in ancestors.includeHashes()) {
                            specific.add(hash, filter)
                            ancestorSpecific.add(hash, filter)
                        }
                    }
                    if (filter.hostRegexes != null) regexSpecific.add(filter)
                }
            }
        }

        fun build(): CosmeticIndex {
            val low = HashMap<String, MutableList<String>>()
            val high = ArrayList<String>()
            for (f in genericHide) {
                if (f.key in genericExceptions) continue
                val token = leadingToken(f.selector)
                if (token != null) low.getOrPut(token) { ArrayList(1) }.add(f.selector) else high.add(f.selector)
            }
            return CosmeticIndex(
                specific = specific.build(),
                ancestorSpecific = ancestorSpecific.build(),
                regexSpecific = regexSpecific.toList(),
                negatedGeneric = negatedGeneric.filter { it.key !in genericExceptions && it.css != null },
                genericScriptlets = genericScriptlets.filter { it.key !in genericExceptions },
                genericExceptions = genericExceptions.toHashSet(),
                lowGeneric = low.mapValues { (_, v) -> v.toList() },
                highGenericSelectors = high,
                cosmeticCount = cosmeticCount,
                scriptletCount = scriptletCount,
            )
        }

        /** `.ad-banner > img` → `.ad-banner`; `#sponsor` → `#sponsor`; otherwise null. */
        private fun leadingToken(selector: String): String? {
            if (selector.length < 2 || (selector[0] != '.' && selector[0] != '#')) return null
            var i = 1
            while (i < selector.length) {
                val c = selector[i]
                if (c.isLetterOrDigit() || c == '-' || c == '_') {
                    i++
                    continue
                }
                if (c == '\\') return null
                break
            }
            if (i == 1) return null
            // Only combinators/compound parts may follow; a comma would make it a list.
            if (i < selector.length && selector.indexOf(',', i) != -1) return null
            return selector.substring(0, i)
        }
    }

    companion object {
        private const val HOST_CACHE_SIZE = 128
        private const val PAYLOAD_CACHE_SIZE = 32
        const val EMPTY_PAYLOAD = "{\"css\":\"\",\"procedural\":[],\"generic\":false,\"scriptlets\":[]}"
    }
}

/** Minimal synchronized LRU cache for per-host results. */
internal class LruCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }
}

/** JSON string quoting without a JSON library on the hot path. */
internal object Json {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
