package com.elewashy.nexa.feature.browser.data.adblock.engine

import java.util.regex.Pattern

/**
 * A compiled network filter (ABP/uBO/AdGuard syntax).
 *
 * Immutable after construction except for the lazily compiled regex, which
 * is published through a volatile field so concurrent WebView IO threads can
 * share it safely.
 */
class NetworkFilter internal constructor(
    val flags: Int,
    /** [RequestType] bits this filter applies to. */
    val typeMask: Int,
    /** Page-level exception bits ([PageOption]); exceptions only. */
    val pageOptions: Int,
    /** HTTP method bits ([Method]); 0 = any. */
    val methodMask: Int,
    /** Pattern body without anchors; lowercase unless [MATCH_CASE]. Regex source for [REGEX]. */
    val pattern: String,
    /** Length of the leading hostname part of a `||host^…` pattern, or -1. */
    val hostnameLength: Int,
    val domains: DomainConstraint?,
    val toDomains: DomainConstraint?,
    val denyAllow: DomainConstraint?,
    /** Redirect resource name for `redirect=` / `redirect-rule=`. */
    val redirect: String?,
    val redirectPriority: Int,
    /**
     * Value of a modifier filter: the parameter spec of [REMOVEPARAM] (empty
     * = every parameter) or the policy of [CSP] (empty on an exception =
     * every policy).
     */
    val modifier: String?,
    /** Original filter text, kept for diagnostics. */
    val text: String,
) {
    @Volatile
    private var compiledRegex: Pattern? = null

    val isException: Boolean get() = flags and EXCEPTION != 0
    val isImportant: Boolean get() = flags and IMPORTANT != 0

    /** Filters without positive `domain=` entries are "generic" (see `$genericblock`). */
    val isGeneric: Boolean get() = domains?.hasIncludes != true

    /**
     * Evaluates the cheap constraints first (type, method, party, domains),
     * then the pattern. `denyallow=` skips the filter for listed request hosts.
     */
    fun matches(request: FilterRequest): Boolean {
        val types = if (flags and EXCEPTION != 0) request.exceptionTypes else request.type
        if (typeMask and types == 0) return false
        if (methodMask != 0 && methodMask and request.method == 0) return false
        if (flags and PARTY_MASK != 0 && !matchesParty(request)) return false
        if (domains != null && !domains.matches(request.pageContext)) return false
        if (toDomains != null && !toDomains.matches(request.hostContext)) return false
        if (denyAllow != null && denyAllow.matches(request.hostContext)) return false
        return matchesPattern(request)
    }

    private fun matchesParty(request: FilterRequest): Boolean {
        if (flags and THIRD_PARTY != 0 && !request.isThirdParty) return false
        if (flags and FIRST_PARTY != 0 && request.isThirdParty) return false
        if (flags and STRICT_THIRD_PARTY != 0 && request.host == request.pageContext.host) return false
        if (flags and STRICT_FIRST_PARTY != 0 && request.host != request.pageContext.host) return false
        return true
    }

    internal fun matchesPattern(request: FilterRequest): Boolean {
        if (flags and REGEX != 0) return regex().matcher(request.rawUrl).find()
        val url = if (flags and MATCH_CASE != 0) request.rawUrl else request.url
        if (pattern.isEmpty()) return true
        val anchoredEnd = flags and RIGHT_ANCHOR != 0
        return when {
            flags and HOST_ANCHOR != 0 -> {
                var start = request.hostStart
                val end = request.hostEnd
                while (start < end) {
                    if (Glob.match(url, start, pattern, anchoredEnd)) return true
                    val dot = url.indexOf('.', start)
                    if (dot == -1 || dot >= end) break
                    start = dot + 1
                }
                false
            }
            flags and LEFT_ANCHOR != 0 -> Glob.match(url, 0, pattern, anchoredEnd)
            else -> Glob.find(url, pattern, anchoredEnd)
        }
    }

    private fun regex(): Pattern {
        compiledRegex?.let { return it }
        val compiled = try {
            Pattern.compile(pattern, if (flags and MATCH_CASE != 0) 0 else Pattern.CASE_INSENSITIVE)
        } catch (_: RuntimeException) {
            NEVER_MATCH
        }
        compiledRegex = compiled
        return compiled
    }

    override fun toString(): String = text

    companion object {
        const val EXCEPTION = 1
        const val IMPORTANT = 1 shl 1
        const val MATCH_CASE = 1 shl 2
        const val THIRD_PARTY = 1 shl 3
        const val FIRST_PARTY = 1 shl 4
        const val STRICT_THIRD_PARTY = 1 shl 5
        const val STRICT_FIRST_PARTY = 1 shl 6
        const val HOST_ANCHOR = 1 shl 7
        const val LEFT_ANCHOR = 1 shl 8
        const val RIGHT_ANCHOR = 1 shl 9
        const val REGEX = 1 shl 10
        /** `redirect=`: blocks and redirects. */
        const val REDIRECT_BLOCKING = 1 shl 11
        /** `redirect-rule=`: redirects only requests blocked by another filter. */
        const val REDIRECT_RULE = 1 shl 12
        /** Pure hostname filter (`||host^` without options) — eligible for strict document blocking. */
        const val PURE_HOSTNAME = 1 shl 13
        /** `$removeparam`: strips query parameters instead of blocking. */
        const val REMOVEPARAM = 1 shl 14
        /** `$csp`: adds a Content-Security-Policy to matching documents instead of blocking. */
        const val CSP = 1 shl 15

        /** Filters that modify requests rather than block them. */
        internal const val MODIFIER_MASK = REMOVEPARAM or CSP

        internal const val PARTY_MASK = THIRD_PARTY or FIRST_PARTY or STRICT_THIRD_PARTY or STRICT_FIRST_PARTY

        private val NEVER_MATCH: Pattern = Pattern.compile("(?!)")
    }
}

/** Page-level exception options (only meaningful on `@@` filters). */
object PageOption {
    const val DOCUMENT = 1
    const val ELEMHIDE = 1 shl 1
    const val GENERICHIDE = 1 shl 2
    const val SPECIFICHIDE = 1 shl 3
    const val GENERICBLOCK = 1 shl 4
    const val JSINJECT = 1 shl 5
    const val URLBLOCK = 1 shl 6

    /** Options that disable all network blocking on the page. */
    const val DISABLE_NETWORK = DOCUMENT or URLBLOCK
}

/** HTTP method bits for `$method=`. */
object Method {
    const val GET = 1
    const val POST = 1 shl 1
    const val PUT = 1 shl 2
    const val DELETE = 1 shl 3
    const val HEAD = 1 shl 4
    const val OPTIONS = 1 shl 5
    const val PATCH = 1 shl 6
    const val CONNECT = 1 shl 7
    const val TRACE = 1 shl 8
    const val ALL = (1 shl 9) - 1

    fun fromName(name: String): Int = when (name.lowercase()) {
        "get" -> GET
        "post" -> POST
        "put" -> PUT
        "delete" -> DELETE
        "head" -> HEAD
        "options" -> OPTIONS
        "patch" -> PATCH
        "connect" -> CONNECT
        "trace" -> TRACE
        else -> 0
    }
}

/**
 * ABP wildcard matching: `*` matches any run, `^` matches a separator
 * character or the end of the URL. Iterative two-pointer algorithm with a
 * single backtrack point — linear for typical filters and immune to the
 * catastrophic backtracking of naive regex translation.
 */
internal object Glob {

    fun isSeparator(c: Char): Boolean =
        !(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '%')

    /** Matches [pattern] against [s] starting exactly at [start]. */
    fun match(s: String, start: Int, pattern: String, anchoredEnd: Boolean): Boolean {
        val sLen = s.length
        val pLen = pattern.length
        var si = start
        var pi = 0
        var starP = -1
        var starS = -1
        while (true) {
            if (pi < pLen && pattern[pi] == '*') {
                starP = pi++
                starS = si
                continue
            }
            if (pi == pLen) {
                if (!anchoredEnd || si == sLen) return true
            } else if (si < sLen) {
                val pc = pattern[pi]
                if (if (pc == '^') isSeparator(s[si]) else pc == s[si]) {
                    pi++
                    si++
                    continue
                }
            } else if (pattern[pi] == '^') {
                // `^` also matches the end of the address.
                pi++
                continue
            }
            if (starP >= 0 && starS < sLen) {
                pi = starP + 1
                si = ++starS
                continue
            }
            return false
        }
    }

    /** Unanchored search: tries every start where the first literal character occurs. */
    fun find(s: String, pattern: String, anchoredEnd: Boolean): Boolean {
        val first = pattern[0]
        if (first == '*') return match(s, 0, pattern, anchoredEnd)
        if (first == '^') {
            for (i in 0..s.length) if (match(s, i, pattern, anchoredEnd)) return true
            return false
        }
        var i = s.indexOf(first)
        while (i != -1) {
            if (match(s, i, pattern, anchoredEnd)) return true
            i = s.indexOf(first, i + 1)
        }
        return false
    }
}
