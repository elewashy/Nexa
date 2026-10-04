package com.elewashy.nexa.feature.adblock.data.engine

/**
 * Per-request lookup keys computed once and shared by every index:
 * request-host suffix hashes, page-host suffix (and entity) hashes, and the
 * URL's token hashes.
 */
internal class RequestKeys(request: FilterRequest) {
    val hostSuffixes: LongArray = suffixHashes(request.host, null)
    val pageSuffixes: LongArray = suffixHashes(request.pageContext.host, request.pageContext)
    val tokens: LongArray = tokenize(request.url)

    private companion object {
        const val MAX_TOKENS = 64

        fun suffixHashes(host: String, ctx: HostContext?): LongArray {
            if (host.isEmpty()) return LongArray(0)
            val out = LongArray(32)
            var n = 0
            val entityEnd = if (ctx != null && ctx.publicSuffixLength > 0) host.length - ctx.publicSuffixLength else -1
            var start = 0
            while (n < out.size - 1) {
                out[n++] = Fnv.hash(host, start, host.length)
                if (start < entityEnd) out[n++] = Fnv.entity(host, start, entityEnd)
                val dot = host.indexOf('.', start)
                if (dot == -1) break
                start = dot + 1
            }
            return out.copyOf(n)
        }

        fun tokenize(url: String): LongArray {
            val out = LongArray(MAX_TOKENS)
            var n = 0
            var i = 0
            val len = url.length
            while (i < len && n < MAX_TOKENS) {
                while (i < len && !NetworkFilterIndex.isTokenChar(url[i])) i++
                val start = i
                while (i < len && NetworkFilterIndex.isTokenChar(url[i])) i++
                if (i > start) out[n++] = Fnv.hash(url, start, i)
            }
            return out.copyOf(n)
        }
    }
}

/**
 * Index of network filters, mirroring the uBO/ABP design:
 *  - `||host^…` filters are bucketed by the hash of their hostname and found
 *    by walking the request host's label suffixes;
 *  - other filters are bucketed under their rarest whole token (a run of
 *    `[a-z0-9%]` bounded by separators), found by tokenizing the URL;
 *  - token-less filters restricted to `domain=` sites are bucketed under those
 *    page hostnames;
 *  - the remainder (kept small) is evaluated for every request.
 * Every filter lives in exactly one bucket, so each request touches only the
 * handful of filters that could possibly match.
 */
internal class NetworkFilterIndex internal constructor(
    internal val byHost: SortedLongMap<NetworkFilter>,
    internal val byToken: SortedLongMap<NetworkFilter>,
    internal val byPage: SortedLongMap<NetworkFilter>,
    internal val unindexed: List<NetworkFilter>,
    val size: Int,
) {

    /** First filter accepted by [predicate] among those matching [request]. */
    fun find(
        request: FilterRequest,
        keys: RequestKeys,
        predicate: (NetworkFilter) -> Boolean = { true },
    ): NetworkFilter? {
        if (size == 0) return null
        for (h in keys.hostSuffixes) byHost[h]?.let { bucket -> scan(bucket, request, predicate)?.let { return it } }
        for (t in keys.tokens) byToken[t]?.let { bucket -> scan(bucket, request, predicate)?.let { return it } }
        for (h in keys.pageSuffixes) byPage[h]?.let { bucket -> scan(bucket, request, predicate)?.let { return it } }
        return scan(unindexed, request, predicate)
    }

    /** Invokes [action] for every matching filter (used for page options and redirects). */
    fun forEachMatch(request: FilterRequest, keys: RequestKeys, action: (NetworkFilter) -> Unit) {
        if (size == 0) return
        val seen = HashSet<NetworkFilter>()
        val visit: (NetworkFilter) -> Boolean = { f ->
            if (f.matches(request) && seen.add(f)) action(f)
            false
        }
        for (h in keys.hostSuffixes) byHost[h]?.forEach { visit(it) }
        for (t in keys.tokens) byToken[t]?.forEach { visit(it) }
        for (h in keys.pageSuffixes) byPage[h]?.forEach { visit(it) }
        unindexed.forEach { visit(it) }
    }

    private inline fun scan(
        bucket: List<NetworkFilter>,
        request: FilterRequest,
        predicate: (NetworkFilter) -> Boolean,
    ): NetworkFilter? {
        for (i in bucket.indices) {
            val f = bucket[i]
            if (predicate(f) && f.matches(request)) return f
        }
        return null
    }

    fun filters(): Sequence<NetworkFilter> =
        byHost.values() + byToken.values() + byPage.values() + unindexed.asSequence()

    class Builder {
        private val filters = ArrayList<NetworkFilter>()

        fun add(filter: NetworkFilter) {
            filters.add(filter)
        }

        val count: Int get() = filters.size

        /** Candidate tokens per filter; [tokenFrequency] is shared across indexes for better choices. */
        fun collectTokens(tokenFrequency: HashMap<Long, Int>): List<LongArray> =
            filters.map { f ->
                val tokens = if (f.hostnameLength > 0) LongArray(0) else candidateTokens(f)
                for (t in tokens) tokenFrequency.merge(t, 1, Int::plus)
                tokens
            }

        fun build(tokens: List<LongArray>, tokenFrequency: Map<Long, Int>): NetworkFilterIndex {
            val byHost = SortedLongMap.Builder<NetworkFilter>()
            val byToken = SortedLongMap.Builder<NetworkFilter>()
            val byPage = SortedLongMap.Builder<NetworkFilter>()
            val unindexed = ArrayList<NetworkFilter>()
            for ((i, f) in filters.withIndex()) {
                if (f.hostnameLength > 0) {
                    byHost.add(Fnv.hash(f.pattern.substring(0, f.hostnameLength).lowercase()), f)
                    continue
                }
                val candidates = tokens[i]
                if (candidates.isNotEmpty()) {
                    var best = candidates[0]
                    var bestScore = Int.MAX_VALUE
                    for (t in candidates) {
                        val score = (tokenFrequency[t] ?: 0) + if (t in BAD_TOKENS) BAD_TOKEN_PENALTY else 0
                        if (score < bestScore) {
                            bestScore = score
                            best = t
                        }
                    }
                    byToken.add(best, f)
                    continue
                }
                val includes = f.domains?.takeIf { it.hasIncludes }?.includeHashes()
                if (includes != null) {
                    for (h in includes) byPage.add(h, f)
                } else {
                    unindexed.add(f)
                }
            }
            return NetworkFilterIndex(byHost.build(), byToken.build(), byPage.build(), unindexed, filters.size)
        }
    }

    companion object {
        val EMPTY = Builder().let { it.build(it.collectTokens(HashMap()), emptyMap()) }

        private const val BAD_TOKEN_PENALTY = 1_000_000

        /** Tokens present in nearly every URL; never worth bucketing under. */
        private val BAD_TOKENS: Set<Long> = listOf(
            "http", "https", "www", "com", "net", "org", "js", "html", "php", "css", "png", "jpg", "gif",
            "cdn", "static", "assets", "min", "v1", "id", "ref", "utm", "source", "x", "s", "a", "p",
        ).map { Fnv.hash(it) }.toSet()

        fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '%' || c in 'A'..'Z'

        /**
         * Whole tokens of a plain pattern: a run of token characters bounded on
         * both sides by a separator, `^`, or an anchor — never by `*` or an
         * unanchored pattern end, where the URL token could be longer.
         */
        fun candidateTokens(filter: NetworkFilter): LongArray {
            if (filter.flags and NetworkFilter.REGEX != 0) return regexTokens(filter.pattern)
            val p = filter.pattern.lowercase()
            val leftAnchored = filter.flags and (NetworkFilter.HOST_ANCHOR or NetworkFilter.LEFT_ANCHOR) != 0
            val rightAnchored = filter.flags and NetworkFilter.RIGHT_ANCHOR != 0
            val out = ArrayList<Long>(4)
            var i = 0
            while (i < p.length) {
                if (!isTokenChar(p[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < p.length && isTokenChar(p[i])) i++
                val leftOk = if (start == 0) leftAnchored else p[start - 1] != '*'
                val rightOk = if (i == p.length) rightAnchored else p[i] != '*'
                if (leftOk && rightOk) out.add(Fnv.hash(p, start, i))
            }
            return out.toLongArray()
        }

        /**
         * Conservative literal extraction from a regex: only runs of
         * `[a-z0-9]` outside groups/classes, preceded and followed by a
         * literal separator, and not quantified. Alternations disable
         * tokenization entirely.
         */
        fun regexTokens(source: String): LongArray {
            val s = source.lowercase()
            var depth = 0
            var inClass = false
            var k = 0
            while (k < s.length) {
                val c = s[k]
                when {
                    c == '\\' -> k++
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                    c == '(' -> depth++
                    c == ')' -> depth--
                    c == '|' && depth == 0 -> return LongArray(0)
                }
                k++
            }
            val out = ArrayList<Long>(2)
            var i = 0
            depth = 0
            inClass = false
            var previousLiteralSeparator = false
            while (i < s.length) {
                val c = s[i]
                if (inClass) {
                    if (c == '\\') i++ else if (c == ']') inClass = false
                    i++
                    previousLiteralSeparator = false
                    continue
                }
                when {
                    c == '\\' && i + 1 < s.length -> {
                        val next = s[i + 1]
                        previousLiteralSeparator = depth == 0 && !next.isLetterOrDigit() && !isQuantifier(s, i + 2)
                        i += 2
                    }
                    c == '[' -> {
                        inClass = true
                        i++
                        previousLiteralSeparator = false
                    }
                    c == '(' || c == ')' -> {
                        if (c == '(') depth++ else depth--
                        i++
                        previousLiteralSeparator = false
                    }
                    c in 'a'..'z' || c in '0'..'9' -> {
                        val start = i
                        while (i < s.length && (s[i] in 'a'..'z' || s[i] in '0'..'9')) i++
                        val end = i
                        val leftOk = previousLiteralSeparator
                        val next = if (end < s.length) s[end] else null
                        val nextIsQuantifier = next == '?' || next == '*' || next == '+' || next == '{'
                        val rightOk = when {
                            next == null -> false
                            next == '\\' -> end + 1 < s.length && !s[end + 1].isLetterOrDigit() && !isQuantifier(s, end + 2)
                            next == '/' || next == '-' || next == '=' || next == '&' || next == '_' || next == ':' ->
                                !isQuantifier(s, end + 1)
                            next == '$' -> end == s.length - 1
                            else -> false
                        }
                        if (depth == 0 && leftOk && rightOk && !nextIsQuantifier && end - start >= 2) {
                            out.add(Fnv.hash(s, start, end))
                        }
                        previousLiteralSeparator = false
                    }
                    c == '/' || c == '-' || c == '=' || c == '&' || c == '_' || c == ':' -> {
                        previousLiteralSeparator = depth == 0 && !isQuantifier(s, i + 1)
                        i++
                    }
                    else -> {
                        previousLiteralSeparator = false
                        i++
                    }
                }
            }
            return out.toLongArray()
        }

        private fun isQuantifier(s: String, index: Int): Boolean {
            if (index >= s.length) return false
            val c = s[index]
            return c == '?' || c == '*' || c == '+' || c == '{'
        }
    }
}
