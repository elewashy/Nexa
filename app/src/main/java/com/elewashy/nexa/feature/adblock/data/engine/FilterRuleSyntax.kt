package com.elewashy.nexa.feature.adblock.data.engine

import com.elewashy.nexa.feature.adblock.domain.model.RuleKind

/**
 * Single-rule helpers for the custom rules editor: classification with the
 * very parsers the engine compiles with (so what the editor reports is what
 * the engine does) and normalization of convenient input forms.
 */
object FilterRuleSyntax {

    /** Longest rule accepted from the editor; real filters are far shorter. */
    const val MAX_RULE_LENGTH = 4096

    /** What [rule] does once compiled; [trusted] = trusted-only scriptlets allowed. */
    fun classify(rule: String, trusted: Boolean): RuleKind {
        val line = rule.trim()
        if (line.isEmpty() || line.length > MAX_RULE_LENGTH) return RuleKind.Unsupported
        val first = line[0]
        if (first == '!') return RuleKind.Comment
        if (first == '[' && line.endsWith("]")) return RuleKind.Comment
        if (first == '#' && !FilterListParser.isCosmeticStart(line)) return RuleKind.Comment

        if (line.indexOf('#') != -1) {
            when (val result = CosmeticFilterParser.parse(line) { ScriptletCatalog.resolve(it, trusted) }) {
                is CosmeticParseResult.Filter -> return when {
                    result.filter.kind == CosmeticFilter.Kind.SCRIPTLET -> RuleKind.Scriptlet
                    result.filter.exception -> RuleKind.ElementHidingException
                    else -> RuleKind.ElementHiding
                }
                CosmeticParseResult.Unsupported -> {
                    val trustedResult = if (trusted) null else CosmeticFilterParser.parse(line) {
                        ScriptletCatalog.resolve(it, trustedSource = true)
                    }
                    return if (trustedResult is CosmeticParseResult.Filter) RuleKind.RequiresTrust else RuleKind.Unsupported
                }
                CosmeticParseResult.NotCosmetic -> Unit
            }
        }

        FilterListParser.hostsFileHost(line)?.let { hosts ->
            return if (hosts.isEmpty()) RuleKind.Unsupported else RuleKind.Block
        }
        if (FilterListParser.isPlainHostname(line)) return RuleKind.Block
        return when (val result = NetworkFilterParser.parse(line)) {
            is NetworkParseResult.BadFilter -> RuleKind.BadFilter
            NetworkParseResult.Unsupported -> RuleKind.Unsupported
            is NetworkParseResult.Filter -> {
                val f = result.filter
                val modifierOnly = f.flags and NetworkFilter.MODIFIER_MASK != 0 ||
                    f.flags and NetworkFilter.REDIRECT_RULE != 0
                when {
                    modifierOnly -> RuleKind.Modifier
                    f.isException -> RuleKind.Allow
                    f.isImportant -> RuleKind.ImportantBlock
                    else -> RuleKind.Block
                }
            }
        }
    }

    /** How the editor should read input that is not already filter syntax. */
    enum class Intent { Block, Allow }

    /**
     * Turns convenient input into filter syntax:
     *  - a URL (`https://ads.example/x.js`) → `||ads.example/x.js`;
     *  - a bare hostname (`ads.example`) → `||ads.example^`;
     *  - with [Intent.Allow], a network rule becomes an exception (`@@…`)
     *    and a cosmetic rule a cosmetic exception (`#@#…`).
     * Anything else (including rules that are already exceptions) is
     * returned trimmed. Returns null for blank input.
     */
    fun normalize(input: String, intent: Intent): String? {
        var rule = input.trim()
        if (rule.isEmpty()) return null
        if (rule.startsWith('!')) return rule

        rule = urlToPattern(rule) ?: rule
        if (FilterListParser.isPlainHostname(rule)) rule = "||${rule.lowercase()}^"
        if (intent == Intent.Block) return rule

        val cosmeticSeparator = COSMETIC_SEPARATORS.firstOrNull { rule.contains(it.first) }
        return when {
            rule.startsWith("@@") -> rule
            COSMETIC_EXCEPTIONS.any { rule.contains(it) } -> rule
            cosmeticSeparator != null -> rule.replaceFirst(cosmeticSeparator.first, cosmeticSeparator.second)
            else -> "@@$rule"
        }
    }

    /** `http(s)://host/path?query` → `||host/path?query` (`||host^` without a path), else null. */
    private fun urlToPattern(input: String): String? {
        val lower = input.lowercase()
        val schemeEnd = when {
            lower.startsWith("https://") -> 8
            lower.startsWith("http://") -> 7
            else -> return null
        }
        if (input.any { it.isWhitespace() }) return null
        val rest = input.substring(schemeEnd).substringBefore('#')
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == ':' }.let { if (it == -1) rest.length else it }
        val host = Hostnames.normalize(rest.substring(0, hostEnd)) ?: return null
        if (!Hostnames.isValidHostname(host)) return null
        val tail = rest.substring(hostEnd).let { t ->
            // Drop an explicit port: filters match on host, not port.
            if (t.startsWith(':')) t.dropWhile { it != '/' && it != '?' } else t
        }
        return if (tail.isEmpty() || tail == "/") "||$host^" else "||$host$tail"
    }

    /** Separator → exception form, most specific first so `#?#` is not read as `##`. */
    private val COSMETIC_SEPARATORS = listOf(
        "#?#" to "#@?#",
        "#$#" to "#@$#",
        "#%#" to "#@%#",
        "##" to "#@#",
    )
    private val COSMETIC_EXCEPTIONS = listOf("#@#", "#@?#", "#@$#", "#@%#")
}
