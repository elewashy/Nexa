package com.elewashy.nexa.feature.adblock.data.engine

import java.net.URLDecoder

/**
 * `$removeparam` semantics (uBO): a spec is a parameter name, `/regex/flags`
 * (tested against `name=value`), either of them negated with `~` (strip
 * every parameter *except* the matching ones), or empty (strip them all).
 */
internal object RemoveParams {

    /** Compiles a `/regex/flags` spec; null when it is not a valid regex. */
    fun compileRegex(spec: String): Regex? {
        val close = spec.lastIndexOf('/')
        if (!spec.startsWith('/') || close <= 0) return null
        val flags = spec.substring(close + 1)
        if (flags.any { it != 'i' }) return null
        return try {
            if (flags.isEmpty()) Regex(spec.substring(1, close)) else Regex(spec.substring(1, close), RegexOption.IGNORE_CASE)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Returns [url] without the query parameters selected by [specs], or null
     * when nothing is removed. Order and encoding of the kept parameters and
     * the fragment are preserved.
     */
    fun strip(url: String, specs: Collection<String>): String? {
        val queryStart = url.indexOf('?')
        if (queryStart == -1 || specs.isEmpty()) return null
        val hash = url.indexOf('#', queryStart)
        val queryEnd = if (hash == -1) url.length else hash
        if (queryEnd == queryStart + 1) return null
        val matchers = specs.map(::matcher)
        val parts = url.substring(queryStart + 1, queryEnd).split('&')
        val kept = parts.filter { part -> part.isEmpty() || matchers.none { remove -> remove(part) } }
        if (kept.size == parts.size) return null
        val query = kept.filter { it.isNotEmpty() }.joinToString("&")
        return url.substring(0, queryStart) + (if (query.isEmpty()) "" else "?$query") + url.substring(queryEnd)
    }

    /** Predicate deciding whether a raw `name=value` pair must be removed. */
    private fun matcher(spec: String): (String) -> Boolean {
        if (spec.isEmpty()) return { true }
        val negated = spec.startsWith('~')
        val body = if (negated) spec.substring(1) else spec
        val regex = if (body.startsWith('/')) compileRegex(body) else null
        val selected: (String) -> Boolean = if (regex != null) {
            { pair -> regex.containsMatchIn(decode(pair)) }
        } else {
            { pair -> decode(pair.substringBefore('=')) == body }
        }
        return if (negated) { pair -> !selected(pair) } else selected
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        s
    }
}
