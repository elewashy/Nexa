package com.elewashy.nexa.feature.adblock.data.engine

/**
 * A compiled cosmetic, procedural or scriptlet filter.
 *
 * @property key canonical identity used to match `#@#` exceptions.
 * @property selector selector (or raw procedural expression for [Kind.PROCEDURAL]).
 * @property scriptlet scriptlet invocation for [Kind.SCRIPTLET].
 */
class CosmeticFilter internal constructor(
    val kind: Kind,
    val exception: Boolean,
    val domains: DomainConstraint?,
    /**
     * uBO `site>>` entries: the filter applies to documents on those sites
     * and to every frame embedded in them (matched against the top document).
     */
    val ancestors: DomainConstraint?,
    /** uBO `/regex/` hostname entries, matched against the document's hostname. */
    internal val hostRegexes: List<Regex>?,
    val key: String,
    val selector: String,
    /** Full rule for [Kind.STYLE]; hide rules are derived from [selector] on demand to save memory. */
    internal val styleCss: String?,
    val scriptlet: ScriptletCall?,
) {
    enum class Kind { HIDE, STYLE, PROCEDURAL, SCRIPTLET }

    /** CSS rule text for [Kind.HIDE] / [Kind.STYLE]; null otherwise. */
    val css: String?
        get() = when (kind) {
            Kind.HIDE -> CosmeticFilterParser.hideRule(selector)
            Kind.STYLE -> styleCss
            else -> null
        }

    val isGeneric: Boolean get() = domains == null && ancestors == null && hostRegexes == null

    /** Only `~negated` hostnames: applies everywhere except those sites. */
    val isNegatedGeneric: Boolean
        get() = ancestors == null && hostRegexes == null && domains != null && !domains.hasIncludes

    /** Whether this site-specific filter applies to [frame], embedded in [top] (null = top level). */
    internal fun appliesTo(frame: HostContext, top: HostContext?): Boolean {
        val positive = ancestors != null || hostRegexes != null
        if (domains != null && (domains.hasIncludes || !positive) && domains.matches(frame)) return true
        if (hostRegexes != null && hostRegexes.any { it.containsMatchIn(frame.host) }) return true
        if (ancestors == null) return false
        return ancestors.matches(frame) || (top != null && ancestors.matches(top))
    }
}

/** One scriptlet invocation; [args] are raw strings passed as JS string arguments. */
class ScriptletCall(val name: String, val args: List<String>) {
    val key: String = buildString {
        append(name)
        for (a in args) append('\u0000').append(a)
    }
}

internal sealed interface CosmeticParseResult {
    class Filter(val filter: CosmeticFilter) : CosmeticParseResult
    data object Unsupported : CosmeticParseResult
    /** Not cosmetic syntax at all — parse as a network filter. */
    data object NotCosmetic : CosmeticParseResult
}

/**
 * Parses element hiding (`##`, `#@#`), extended/procedural CSS (`#?#`,
 * uBO procedural operators), CSS injection (`#$#`, `:style()`), uBO
 * scriptlet injection (`##+js(…)`) and AdGuard scriptlets
 * (`#%#//scriptlet(…)`). Hostname lists accept entities (`site.*`),
 * negations and uBO's ancestor form (`site>>`). HTML filtering (`##^`,
 * `$$`) and raw JavaScript rules need response rewriting or arbitrary code
 * and are unsupported.
 */
internal object CosmeticFilterParser {

    private val SEPARATORS = arrayOf(
        "#@\$?#", "#\$?#", "#@\$#", "#@%#", "#@?#", "#@#", "#\$#", "#%#", "#?#", "##",
    )

    /** uBO/AdGuard procedural operators we evaluate in the content script. */
    private val PROCEDURAL_OPERATORS = listOf(
        ":has-text(", ":-abp-contains(", ":contains(", ":upward(", ":nth-ancestor(", ":xpath(",
        ":matches-css(", ":matches-css-before(", ":matches-css-after(", ":min-text-length(",
        ":matches-attr(", ":matches-path(", ":matches-media(", ":remove()", ":remove-attr(",
        ":remove-class(", ":style(", ":if(", ":if-not(", ":watch-attr(", ":others()", ":shadow(",
        ":matches-prop(",
    )

    /** Operators that need capabilities we do not emulate. */
    private val UNSUPPORTED_OPERATORS = listOf(":spath(", ":-abp-properties(")

    fun parse(line: String, scriptletResolver: (String) -> String?): CosmeticParseResult {
        val (sepIndex, separator) = findSeparator(line) ?: return CosmeticParseResult.NotCosmetic
        val domainPart = line.substring(0, sepIndex)
        val domainEntries = splitDomainList(domainPart) ?: return CosmeticParseResult.NotCosmetic
        val body = line.substring(sepIndex + separator.length).trim()
        if (body.isEmpty()) return CosmeticParseResult.Unsupported

        val exception = separator.startsWith("#@")
        val domains = parseDomains(domainEntries) ?: return CosmeticParseResult.Unsupported

        return when (separator) {
            "#%#", "#@%#" -> parseAdGuardScriptlet(body, exception, domains, scriptletResolver)
            "#\$#", "#@\$#" -> parseCssInjection(body, exception, domains)
            "#\$?#", "#@\$?#" -> parseCssInjection(body, exception, domains, procedural = true)
            "#?#", "#@?#" -> parseSelector(body, exception, domains)
            else -> when {
                body.startsWith("+js(") -> parseUboScriptlet(body, exception, domains, scriptletResolver)
                body.startsWith("^") -> CosmeticParseResult.Unsupported
                else -> parseSelector(body, exception, domains)
            }
        }.let { result ->
            // Generic procedural / style filters are discarded (as in uBO): they
            // would have to be evaluated on every page. Generic scriptlets
            // (`*##+js(…)`, typically with `~site` exclusions) are kept.
            val filter = (result as? CosmeticParseResult.Filter)?.filter
            if (filter != null && !filter.exception && filter.domains?.hasIncludes != true &&
                filter.ancestors == null && filter.hostRegexes == null &&
                filter.kind != CosmeticFilter.Kind.HIDE && filter.kind != CosmeticFilter.Kind.SCRIPTLET
            ) {
                CosmeticParseResult.Unsupported
            } else {
                result
            }
        }
    }

    private fun findSeparator(line: String): Pair<Int, String>? {
        var from = 0
        while (true) {
            val idx = line.indexOf('#', from)
            if (idx == -1 || idx + 1 >= line.length) return null
            for (sep in SEPARATORS) {
                if (line.startsWith(sep, idx)) return idx to sep
            }
            from = idx + 1
        }
    }

    /**
     * Splits the hostname list before a cosmetic separator, or returns null
     * when it is not one (the line is then parsed as a network filter).
     * `/regex/` entries may contain commas and any regex syntax.
     */
    private fun splitDomainList(part: String): List<String>? {
        val entries = ArrayList<String>()
        var i = 0
        while (i <= part.length) {
            val negated = i < part.length && part[i] == '~'
            val start = if (negated) i + 1 else i
            if (start < part.length && part[start] == '/') {
                // Regex entry: ends at an unescaped `/` followed by `,` or the end.
                var end = -1
                var j = start + 1
                while (j < part.length) {
                    if (part[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (part[j] == '/' && (j + 1 == part.length || part[j + 1] == ',')) {
                        end = j
                        break
                    }
                    j++
                }
                if (end == -1 || end == start + 1) return null
                entries.add(part.substring(i, end + 1))
                i = end + 2
                continue
            }
            val comma = part.indexOf(',', i).let { if (it == -1) part.length else it }
            val entry = part.substring(i, comma)
            for (c in entry) {
                val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' ||
                    c == '~' || c == '*' || c == '>' || c.code > 0x7f
                if (!ok) return null
            }
            entries.add(entry)
            i = comma + 1
        }
        return entries
    }

    /** Hostname list of a cosmetic filter; all parts empty means generic. */
    class Domains(val direct: DomainConstraint?, val ancestors: DomainConstraint?, val regexes: List<Regex>?)

    private val GENERIC = Domains(null, null, null)

    /**
     * Sorts hostname entries into direct (`a.com`, `~b.a.com`, `c.*`),
     * ancestor (`d.com>>`) and regex (`/^moon-.+\.com$/`) entries; null when
     * an entry is invalid or unsupported (negated regexes).
     */
    private fun parseDomains(entries: List<String>): Domains? {
        val direct = ArrayList<String>()
        val ancestors = ArrayList<String>()
        val regexes = ArrayList<Regex>()
        for (raw in entries) {
            val entry = raw.trim()
            if (entry.isEmpty() || entry == "*") continue
            when {
                entry.startsWith('/') -> regexes.add(compileHostRegex(entry.substring(1, entry.length - 1)) ?: return null)
                entry.startsWith("~/") -> return null
                entry.endsWith(">>") -> {
                    val host = entry.substring(0, entry.length - 2)
                    if (host.isEmpty() || host.startsWith('~') || host.contains('>')) return null
                    ancestors.add(host)
                }
                entry.contains('>') -> return null
                else -> direct.add(entry)
            }
        }
        if (direct.isEmpty() && ancestors.isEmpty() && regexes.isEmpty()) return GENERIC
        val directConstraint =
            if (direct.isEmpty()) null else DomainConstraint.parse(direct.joinToString(","), ',') ?: return null
        val ancestorConstraint =
            if (ancestors.isEmpty()) null else DomainConstraint.parse(ancestors.joinToString(","), ',') ?: return null
        return Domains(directConstraint, ancestorConstraint, regexes.ifEmpty { null })
    }

    internal fun compileHostRegex(source: String): Regex? = try {
        Regex(source, RegexOption.IGNORE_CASE)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseSelector(
        body: String,
        exception: Boolean,
        domains: Domains,
    ): CosmeticParseResult {
        if (body.isEmpty()) return CosmeticParseResult.Unsupported
        if (UNSUPPORTED_OPERATORS.any { body.contains(it) }) return CosmeticParseResult.Unsupported
        var selector = body
        val hasProcedural = PROCEDURAL_OPERATORS.any { selector.contains(it) }
        if (!hasProcedural && selector.contains(":-abp-has(")) selector = selector.replace(":-abp-has(", ":has(")

        if (!hasProcedural) {
            if (!isSafeCss(selector)) return CosmeticParseResult.Unsupported
            return filter(CosmeticFilter.Kind.HIDE, exception, domains, selector, selector, null)
        }

        // `selector:style(declarations)` with a plain CSS selector is pure CSS.
        styleSuffix(selector)?.let { (base, declarations) ->
            if (PROCEDURAL_OPERATORS.none { base.contains(it) } && isSafeCss(base) && isSafeDeclarations(declarations)) {
                val css = "$base{$declarations}"
                return filter(CosmeticFilter.Kind.STYLE, exception, domains, "$base:style($declarations)", base, css)
            }
        }
        return filter(CosmeticFilter.Kind.PROCEDURAL, exception, domains, selector, selector, null)
    }

    private fun parseCssInjection(
        body: String,
        exception: Boolean,
        domains: Domains,
        procedural: Boolean = false,
    ): CosmeticParseResult {
        val open = body.lastIndexOf('{')
        if (open <= 0 || !body.endsWith('}')) return CosmeticParseResult.Unsupported
        val selector = body.substring(0, open).trim()
        val declarations = body.substring(open + 1, body.length - 1).trim()
        if (declarations.isEmpty() || !isSafeDeclarations(declarations)) return CosmeticParseResult.Unsupported
        if (declarations.replace(" ", "") == "remove:true;" || declarations.replace(" ", "") == "remove:true") {
            return parseSelector("$selector:remove()", exception, domains)
        }
        if (procedural || PROCEDURAL_OPERATORS.any { selector.contains(it) }) {
            return parseSelector("$selector:style($declarations)", exception, domains)
        }
        if (!isSafeCss(selector)) return CosmeticParseResult.Unsupported
        return filter(
            CosmeticFilter.Kind.STYLE, exception, domains, "$selector:style($declarations)", selector,
            "$selector{$declarations}",
        )
    }

    private fun parseUboScriptlet(
        body: String,
        exception: Boolean,
        domains: Domains,
        resolver: (String) -> String?,
    ): CosmeticParseResult {
        if (!body.endsWith(")")) return CosmeticParseResult.Unsupported
        val inner = body.substring(4, body.length - 1).trim()
        if (inner.isEmpty()) {
            // `#@#+js()` disables every scriptlet on the matching sites.
            if (!exception) return CosmeticParseResult.Unsupported
            return filter(CosmeticFilter.Kind.SCRIPTLET, true, domains, ALL_SCRIPTLETS_KEY, "", null)
        }
        val parts = splitUboArgs(inner)
        val name = scriptletName(parts[0], exception, resolver) ?: return CosmeticParseResult.Unsupported
        return scriptlet(ScriptletCall(name, parts.drop(1)), exception, domains)
    }

    private fun parseAdGuardScriptlet(
        body: String,
        exception: Boolean,
        domains: Domains,
        resolver: (String) -> String?,
    ): CosmeticParseResult {
        if (!body.startsWith("//scriptlet(") || !body.endsWith(")")) return CosmeticParseResult.Unsupported
        val args = parseQuotedArgs(body.substring("//scriptlet(".length, body.length - 1))
            ?: return CosmeticParseResult.Unsupported
        if (args.isEmpty()) return CosmeticParseResult.Unsupported
        val name = scriptletName(args[0], exception, resolver) ?: return CosmeticParseResult.Unsupported
        return scriptlet(ScriptletCall(name, args.drop(1)), exception, domains)
    }

    /**
     * Canonical scriptlet name. Exceptions naming a scriptlet we do not
     * implement (or may not run from this list) are still honoured by name:
     * they can only ever disable something.
     */
    private fun scriptletName(raw: String, exception: Boolean, resolver: (String) -> String?): String? {
        val name = normalizeScriptletName(raw)
        if (name.isEmpty()) return null
        return resolver(name) ?: if (exception) ScriptletCatalog.canonicalName(name) else null
    }

    private fun scriptlet(call: ScriptletCall, exception: Boolean, domains: Domains): CosmeticParseResult =
        CosmeticParseResult.Filter(
            CosmeticFilter(
                CosmeticFilter.Kind.SCRIPTLET, exception, domains.direct, domains.ancestors, domains.regexes, call.key,
                call.name, null, call,
            )
        )

    private fun filter(
        kind: CosmeticFilter.Kind,
        exception: Boolean,
        domains: Domains,
        key: String,
        selector: String,
        css: String?,
    ): CosmeticParseResult =
        CosmeticParseResult.Filter(
            CosmeticFilter(kind, exception, domains.direct, domains.ancestors, domains.regexes, key, selector, css, null)
        )

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Key of `#@#+js()` (disables every scriptlet); not a valid selector, so it never collides. */
    const val ALL_SCRIPTLETS_KEY = "+js()"

    fun hideRule(selector: String): String = "$selector{display:none!important}"

    private fun normalizeScriptletName(raw: String): String {
        val name = raw.trim()
        return if (name.endsWith(".js")) name.substring(0, name.length - 3) else name
    }

    private fun styleSuffix(selector: String): Pair<String, String>? {
        if (!selector.endsWith(")")) return null
        val idx = selector.lastIndexOf(":style(")
        if (idx <= 0) return null
        val declarations = selector.substring(idx + 7, selector.length - 1).trim()
        if (declarations.isEmpty()) return null
        return selector.substring(0, idx).trim() to declarations
    }

    /**
     * Rejects selectors that could break out of the generated rule
     * (`}`/`{`) or carry at-rules/comments. One injected rule per selector
     * keeps an invalid selector from voiding its neighbours.
     */
    private fun isSafeCss(selector: String): Boolean {
        if (selector.isEmpty()) return false
        if (selector.contains('{') || selector.contains('}') || selector.contains("/*") || selector.startsWith("@")) {
            return false
        }
        return true
    }

    private fun isSafeDeclarations(declarations: String): Boolean {
        if (declarations.contains('{') || declarations.contains('}') || declarations.contains("/*")) return false
        val lower = declarations.lowercase()
        // Remote resource loads from injected CSS would leak browsing data.
        return !lower.contains("url(") && !lower.contains("expression(") && !lower.contains("@import")
    }

    /** uBO argument splitting: commas separate, `\,` escapes, quotes group. */
    internal fun splitUboArgs(inner: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i <= inner.length) {
            while (i < inner.length && inner[i] == ' ') i++
            if (i < inner.length && (inner[i] == '\'' || inner[i] == '"' || inner[i] == '`')) {
                val quote = inner[i]
                val sb = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < inner.length) {
                    val c = inner[j]
                    if (c == '\\' && j + 1 < inner.length && inner[j + 1] == quote) {
                        sb.append(quote)
                        j += 2
                        continue
                    }
                    if (c == quote) {
                        closed = true
                        break
                    }
                    sb.append(c)
                    j++
                }
                if (closed) {
                    var k = j + 1
                    while (k < inner.length && inner[k] == ' ') k++
                    if (k >= inner.length || inner[k] == ',') {
                        out.add(sb.toString())
                        i = k + 1
                        if (k >= inner.length) break
                        continue
                    }
                }
            }
            val sb = StringBuilder()
            var j = i
            while (j < inner.length) {
                val c = inner[j]
                if (c == '\\' && j + 1 < inner.length && inner[j + 1] == ',') {
                    sb.append(',')
                    j += 2
                    continue
                }
                if (c == ',') break
                sb.append(c)
                j++
            }
            out.add(sb.toString().trim())
            if (j >= inner.length) break
            i = j + 1
        }
        return out
    }

    /** AdGuard argument list: `'a', "b"` — every argument must be quoted. */
    private fun parseQuotedArgs(inner: String): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == ' ' || c == ',') {
                i++
                continue
            }
            if (c != '\'' && c != '"') return null
            val sb = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < inner.length) {
                val ch = inner[j]
                if (ch == '\\' && j + 1 < inner.length) {
                    sb.append(inner[j + 1])
                    j += 2
                    continue
                }
                if (ch == c) {
                    closed = true
                    break
                }
                sb.append(ch)
                j++
            }
            if (!closed) return null
            out.add(sb.toString())
            i = j + 1
        }
        return out
    }
}
