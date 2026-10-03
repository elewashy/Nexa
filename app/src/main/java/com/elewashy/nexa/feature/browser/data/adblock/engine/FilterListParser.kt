package com.elewashy.nexa.feature.browser.data.adblock.engine

import java.io.BufferedReader

/** Parse statistics for one filter list. */
data class FilterListStats(
    val networkFilters: Int,
    val cosmeticFilters: Int,
    val scriptletFilters: Int,
    val unsupported: Int,
) {
    val total: Int get() = networkFilters + cosmeticFilters + scriptletFilters
}

/**
 * Streams a filter list line by line into a [FilterEngine.Builder].
 *
 * Handles comments, `!#if` / `!#else` / `!#endif` preprocessor blocks
 * (evaluated with the uBO-on-Chromium-mobile environment, so list sections
 * written for other platforms are skipped exactly as uBO would), hosts-file
 * syntax (`0.0.0.0 host`) and plain hostname lines.
 */
internal class FilterListParser(
    private val builder: FilterEngine.Builder,
    private val trusted: Boolean,
    private val onUnsupported: ((String) -> Unit)? = null,
) {

    private var network = 0
    private var cosmetic = 0
    private var scriptlets = 0
    private var unsupported = 0

    /** Stack of "is this block active" flags for `!#if` nesting. */
    private val conditions = ArrayList<Boolean>()
    private var active = true

    fun parse(reader: BufferedReader): FilterListStats {
        while (true) {
            val line = reader.readLine() ?: break
            parseLine(line)
        }
        return FilterListStats(network, cosmetic, scriptlets, unsupported)
    }

    fun parseLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        val first = line[0]
        if (first == '!') {
            if (line.startsWith("!#")) handleDirective(line)
            return
        }
        if (!active) return
        if (first == '[' && line.endsWith("]")) return // [Adblock Plus 2.0] header
        if (first == '#' && !isCosmeticStart(line)) return // hosts-file comment

        if (line.indexOf('#') != -1) {
            when (val cosmeticResult = CosmeticFilterParser.parse(line) { ScriptletCatalog.resolve(it, trusted) }) {
                is CosmeticParseResult.Filter -> {
                    if (builder.addCosmetic(cosmeticResult.filter, line)) {
                        if (cosmeticResult.filter.kind == CosmeticFilter.Kind.SCRIPTLET) scriptlets++ else cosmetic++
                    }
                    return
                }
                CosmeticParseResult.Unsupported -> {
                    reportUnsupported(line)
                    return
                }
                CosmeticParseResult.NotCosmetic -> Unit
            }
        }

        hostsFileHost(line)?.let { hosts ->
            for (host in hosts) if (builder.addHostname(host)) network++
            return
        }
        if (isPlainHostname(line)) {
            if (builder.addHostname(line.lowercase())) network++
            return
        }
        when (val result = NetworkFilterParser.parse(line)) {
            is NetworkParseResult.Filter -> if (builder.addNetwork(result.filter)) network++
            is NetworkParseResult.BadFilter -> builder.addBadFilter(result.target)
            NetworkParseResult.Unsupported -> reportUnsupported(line)
        }
    }

    private fun reportUnsupported(line: String) {
        unsupported++
        onUnsupported?.invoke(line)
    }

    private fun isCosmeticStart(line: String): Boolean =
        line.startsWith("##") || line.startsWith("#@#") || line.startsWith("#?#") || line.startsWith("#@?#") ||
            line.startsWith("#$#") || line.startsWith("#@$#") || line.startsWith("#%#") || line.startsWith("#@%#")

    private fun handleDirective(line: String) {
        when {
            line.startsWith("!#if ") || line == "!#if" -> {
                conditions.add(active)
                active = active && PreprocessorEnv.evaluate(line.substring(4).trim())
            }
            line.startsWith("!#else") -> {
                if (conditions.isEmpty()) return
                val parentActive = conditions.last()
                active = parentActive && !active
            }
            line.startsWith("!#endif") -> {
                if (conditions.isEmpty()) return
                active = conditions.removeAt(conditions.size - 1)
            }
            // `!#include` is resolved by list authors' CDN builds; the lists we ship are pre-assembled.
        }
    }

    /** `0.0.0.0 host [host…] [# comment]` → hostnames, or null if not hosts syntax. */
    private fun hostsFileHost(line: String): List<String>? {
        val space = line.indexOfFirst { it == ' ' || it == '\t' }
        if (space <= 0) return null
        val ip = line.substring(0, space)
        if (ip != "0.0.0.0" && ip != "127.0.0.1" && ip != "::" && ip != "::1" && ip != "::0") return null
        val rest = line.substring(space + 1).substringBefore('#').trim()
        if (rest.isEmpty()) return emptyList()
        return rest.split(' ', '\t').mapNotNull { token ->
            val host = Hostnames.normalize(token) ?: return@mapNotNull null
            host.takeIf { it !in LOCAL_HOSTS && Hostnames.isValidHostname(it) && it.contains('.') }
        }
    }

    /** uBO treats a bare hostname line as `||hostname^`. */
    private fun isPlainHostname(line: String): Boolean {
        if (!line.contains('.') || line.length > 253) return false
        for (c in line) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_'
            if (!ok) return false
        }
        return Hostnames.isValidHostname(line.lowercase()) && !line.endsWith('.')
    }

    private companion object {
        val LOCAL_HOSTS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost")
    }
}

/**
 * `!#if` expression evaluation for the environment we emulate: uBlock Origin
 * running on a Chromium-based mobile browser without HTML filtering.
 */
internal object PreprocessorEnv {
    private val TRUE_TOKENS = setOf("ext_ublock", "env_chromium", "env_mobile", "cap_user_stylesheet")

    fun evaluate(expression: String): Boolean = Evaluator(expression).parseOr()

    private class Evaluator(private val s: String) {
        private var i = 0

        fun parseOr(): Boolean {
            var value = parseAnd()
            while (consume("||")) value = parseAnd() || value
            return value
        }

        private fun parseAnd(): Boolean {
            var value = parseUnary()
            while (consume("&&")) value = parseUnary() && value
            return value
        }

        private fun parseUnary(): Boolean {
            skipSpaces()
            if (consume("!")) return !parseUnary()
            if (consume("(")) {
                val value = parseOr()
                consume(")")
                return value
            }
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            return s.substring(start, i) in TRUE_TOKENS
        }

        private fun consume(token: String): Boolean {
            skipSpaces()
            if (s.startsWith(token, i)) {
                i += token.length
                return true
            }
            return false
        }

        private fun skipSpaces() {
            while (i < s.length && s[i] == ' ') i++
        }
    }
}
