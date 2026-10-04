package com.elewashy.nexa.feature.adblock.data.engine

import java.util.Locale

/** Result of parsing a network filter line. */
internal sealed interface NetworkParseResult {
    class Filter(val filter: NetworkFilter) : NetworkParseResult

    /** `$badfilter`: disables every filter whose canonical text equals [target]. */
    class BadFilter(val target: String) : NetworkParseResult

    /** Valid syntax but relies on capabilities WebView cannot provide; skipped like uBO does. */
    data object Unsupported : NetworkParseResult
}

/**
 * Parses ABP / uBlock Origin / AdGuard network filter syntax.
 *
 * Unknown or unsupported options make the whole filter unsupported instead of
 * silently dropping the option: a filter such as `||x.com^$replace=…` would
 * otherwise degrade into a plain block and break sites — exactly the
 * over-blocking this engine avoids.
 *
 * `$removeparam` and `$csp` become modifier filters ([NetworkFilter.REMOVEPARAM],
 * [NetworkFilter.CSP]) that never block. WebView can only apply them to
 * documents (query stripping before a page load, a policy injected at
 * document start), so modifier filters that cannot target documents are
 * reported unsupported rather than kept as dead weight.
 */
internal object NetworkFilterParser {

    private val UNSUPPORTED_OPTIONS = setOf(
        "replace", "header", "permissions", "urlskip", "urltransform",
        "uritransform", "cookie", "stealth", "cname", "ipaddress", "sitekey", "webrtc", "app", "network",
        "hls", "jsonprune", "xmlprune", "referrerpolicy", "inline-script", "inline-font", "responseheader",
        "addheader", "removeheader", "top", "genericblock-legacy",
    )

    /** Options accepted and ignored (pure annotations). */
    private val IGNORED_OPTIONS = setOf("_", "reason", "content", "extension")

    fun parse(line: String): NetworkParseResult {
        var text = line
        val exception = text.startsWith("@@")
        if (exception) text = text.substring(2)

        val optionsStart = findOptionsStart(text)
        val patternPart = if (optionsStart == -1) text else text.substring(0, optionsStart)
        val optionsPart = if (optionsStart == -1) "" else text.substring(optionsStart + 1)

        val options = OptionState(exception)
        if (optionsPart.isNotEmpty()) {
            for (raw in splitOptions(optionsPart)) {
                if (!options.apply(raw.trim())) return NetworkParseResult.Unsupported
            }
        }
        if (options.badfilter) {
            return NetworkParseResult.BadFilter(canonicalWithoutBadfilter(line))
        }
        val pattern = parsePattern(patternPart, options.matchCase) ?: return NetworkParseResult.Unsupported
        return buildFilter(line, exception, pattern, options)
    }

    private fun buildFilter(
        line: String,
        exception: Boolean,
        pattern: ParsedPattern,
        options: OptionState,
    ): NetworkParseResult {
        var flags = pattern.flags or options.flags
        if (exception) flags = flags or NetworkFilter.EXCEPTION

        if (options.modifierFlag != 0) return buildModifier(line, exception, pattern, options, flags)
        if (options.pageOptions != 0 && !exception) return NetworkParseResult.Unsupported
        if (options.ignoredPageOnly && options.pageOptions == 0 && exception && options.positiveTypes == 0) {
            // `@@…$content` / `$extension` alone: AdGuard-only semantics.
            return NetworkParseResult.Unsupported
        }

        var typeMask = when {
            options.positiveTypes != 0 -> options.positiveTypes and options.negativeTypes.inv()
            options.negativeTypes != 0 -> RequestType.DEFAULT_TYPES and options.negativeTypes.inv()
            else -> RequestType.DEFAULT_TYPES
        }
        if (!exception && typeMask and RequestType.DOCUMENT != 0) {
            // Blocking `$document` blocks the page and its frames.
            typeMask = typeMask or RequestType.SUBDOCUMENT
        }
        if (options.pageOptions != 0) {
            // Page-level exceptions are evaluated against document URLs.
            typeMask = RequestType.DOCUMENT or RequestType.SUBDOCUMENT
        }
        if (typeMask == 0) return NetworkParseResult.Unsupported

        if (pattern.isPureHostname && options.isEmpty) {
            flags = flags or NetworkFilter.PURE_HOSTNAME
        }

        return NetworkParseResult.Filter(
            NetworkFilter(
                flags = flags,
                typeMask = typeMask,
                pageOptions = options.pageOptions,
                methodMask = options.methodMask,
                pattern = pattern.body,
                hostnameLength = pattern.hostnameLength,
                domains = options.domains,
                toDomains = options.toDomains,
                denyAllow = options.denyAllow,
                redirect = options.redirect,
                redirectPriority = options.redirectPriority,
                modifier = null,
                text = line,
            )
        )
    }

    private fun buildModifier(
        line: String,
        exception: Boolean,
        pattern: ParsedPattern,
        options: OptionState,
        patternFlags: Int,
    ): NetworkParseResult {
        val modifierFlag = options.modifierFlag
        // A modifier never combines with blocking-only options.
        if (options.redirect != null || options.pageOptions and PageOption.DOCUMENT.inv() != 0) {
            return NetworkParseResult.Unsupported
        }
        val documents = RequestType.DOCUMENT or RequestType.SUBDOCUMENT
        val explicit = when {
            options.positiveTypes != 0 -> options.positiveTypes and options.negativeTypes.inv()
            options.negativeTypes != 0 -> (RequestType.ALL and RequestType.POPUP.inv()) and options.negativeTypes.inv()
            else -> 0
        }
        val typeMask = if (modifierFlag == NetworkFilter.CSP) {
            // Policies apply to pages and frames.
            if (explicit == 0) documents else explicit and documents
        } else {
            // Query parameters can only be stripped from top-level page loads.
            if (explicit == 0) RequestType.DOCUMENT else explicit and RequestType.DOCUMENT
        }
        if (typeMask == 0) return NetworkParseResult.Unsupported
        val value = options.modifierValue
        if (!exception && modifierFlag == NetworkFilter.CSP && value.isEmpty()) return NetworkParseResult.Unsupported
        return NetworkParseResult.Filter(
            NetworkFilter(
                flags = patternFlags or modifierFlag or if (exception) NetworkFilter.EXCEPTION else 0,
                typeMask = typeMask,
                pageOptions = 0,
                methodMask = options.methodMask,
                pattern = pattern.body,
                hostnameLength = pattern.hostnameLength,
                domains = options.domains,
                toDomains = options.toDomains,
                denyAllow = options.denyAllow,
                redirect = null,
                redirectPriority = 0,
                modifier = value,
                text = line,
            )
        )
    }

    /**
     * Keeps the directives a `<meta>` policy can enforce. `sandbox`,
     * `frame-ancestors` and reporting directives are ignored in meta
     * policies, so they are dropped; null when nothing enforceable remains.
     */
    internal fun metaCspPolicy(raw: String): String? {
        val kept = raw.split(';').map { it.trim() }.filter { directive ->
            val name = directive.substringBefore(' ').lowercase(Locale.ROOT)
            directive.isNotEmpty() && name !in META_IGNORED_DIRECTIVES
        }
        return if (kept.isEmpty()) null else kept.joinToString("; ")
    }

    private val META_IGNORED_DIRECTIVES = setOf("sandbox", "frame-ancestors", "report-uri", "report-to")

    // ── Pattern ─────────────────────────────────────────────────────────

    class ParsedPattern(
        val body: String,
        val flags: Int,
        val hostnameLength: Int,
        val isPureHostname: Boolean,
    )

    fun parsePattern(raw: String, matchCase: Boolean): ParsedPattern? {
        if (raw.length > 2 && raw[0] == '/' && raw[raw.length - 1] == '/' && looksLikeRegex(raw)) {
            val source = raw.substring(1, raw.length - 1)
            return ParsedPattern(source, NetworkFilter.REGEX, -1, false)
        }
        var flags = 0
        var body = raw
        when {
            body.startsWith("||") -> {
                flags = flags or NetworkFilter.HOST_ANCHOR
                body = body.substring(2)
            }
            body.startsWith("|") -> {
                flags = flags or NetworkFilter.LEFT_ANCHOR
                body = body.substring(1)
            }
        }
        if (body.endsWith("|") && !body.endsWith("\\|")) {
            flags = flags or NetworkFilter.RIGHT_ANCHOR
            body = body.substring(0, body.length - 1)
        }
        body = collapseStars(body)
        if (flags and NetworkFilter.HOST_ANCHOR == 0) {
            // A leading `*` is implied for non-anchored patterns.
            if (body.startsWith('*')) {
                body = body.substring(1)
                flags = flags and NetworkFilter.LEFT_ANCHOR.inv()
            }
        } else if (body.startsWith('*')) {
            // `||*foo` is equivalent to an unanchored `foo`.
            body = body.substring(1)
            flags = flags and NetworkFilter.HOST_ANCHOR.inv()
        }
        if (body.endsWith('*') && flags and NetworkFilter.RIGHT_ANCHOR == 0) body = body.trimEnd('*')
        if (body.isEmpty()) flags = flags and (NetworkFilter.HOST_ANCHOR or NetworkFilter.LEFT_ANCHOR or NetworkFilter.RIGHT_ANCHOR).inv()

        if (body.any { it.code > 0x7f }) body = punycodeHostPart(body, flags) ?: return null
        if (!matchCase) body = body.lowercase(Locale.ROOT)
        if (body.any { it == ' ' }) return null

        var hostnameLength = -1
        var pure = false
        if (flags and NetworkFilter.HOST_ANCHOR != 0) {
            var i = 0
            while (i < body.length && isHostChar(body[i])) i++
            val terminator = if (i < body.length) body[i] else null
            val complete = i > 0 && (terminator == '^' || terminator == '/' || terminator == ':' ||
                (terminator == null && flags and NetworkFilter.RIGHT_ANCHOR != 0))
            if (complete && body[0] != '.' && body[i - 1] != '.') hostnameLength = i
            pure = complete && terminator == '^' && i == body.length - 1 && flags and NetworkFilter.RIGHT_ANCHOR == 0 &&
                Hostnames.isValidHostname(body.substring(0, i))
        }
        return ParsedPattern(body, flags, hostnameLength, pure)
    }

    private fun isHostChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_'

    private fun collapseStars(body: String): String {
        if (!body.contains("**")) return body
        val sb = StringBuilder(body.length)
        for (c in body) if (!(c == '*' && sb.isNotEmpty() && sb[sb.length - 1] == '*')) sb.append(c)
        return sb.toString()
    }

    private fun punycodeHostPart(body: String, flags: Int): String? {
        if (flags and NetworkFilter.HOST_ANCHOR == 0) return body
        var end = 0
        while (end < body.length && body[end] != '^' && body[end] != '/' && body[end] != '*' && body[end] != ':') end++
        val host = Hostnames.normalize(body.substring(0, end)) ?: return null
        return host + body.substring(end)
    }

    /**
     * uBO treats `/…/` as a regex only when it is one; `/ads/` style path
     * filters are plain patterns in practice (they contain no regex tokens).
     */
    private fun looksLikeRegex(raw: String): Boolean {
        val inner = raw.substring(1, raw.length - 1)
        if (inner.isEmpty()) return false
        for (c in inner) {
            if (c == '\\' || c == '[' || c == '(' || c == '|' || c == '?' || c == '+' || c == '{' || c == '^' || c == '$' || c == '.') {
                return true
            }
        }
        return false
    }

    // ── Options ─────────────────────────────────────────────────────────

    /** Index of the `$` that starts the options, or -1. */
    fun findOptionsStart(text: String): Int {
        if (text.startsWith('/')) {
            if (text.length > 1 && text.endsWith('/')) return -1
            val regexEnd = text.lastIndexOf("/$")
            if (regexEnd > 0) return regexEnd + 1
        }
        val idx = text.lastIndexOf('$')
        if (idx == -1) return -1
        // `$` inside a URL path followed by more path is not an option separator.
        val tail = text.substring(idx + 1)
        if (tail.isEmpty() || tail.contains('/') && !tail.contains('=')) return -1
        return idx
    }

    private fun splitOptions(options: String): List<String> {
        if (!options.contains('\\')) return options.split(',')
        // Escaped commas (`\,`) appear inside some option values.
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < options.length) {
            val c = options[i]
            if (c == '\\' && i + 1 < options.length && options[i + 1] == ',') {
                sb.append(',')
                i += 2
                continue
            }
            if (c == ',') {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun canonicalWithoutBadfilter(line: String): String {
        val optionsStart = findOptionsStart(if (line.startsWith("@@")) line.substring(2) else line)
            .let { if (it == -1) -1 else it + if (line.startsWith("@@")) 2 else 0 }
        if (optionsStart == -1) return line
        val kept = splitOptions(line.substring(optionsStart + 1)).map { it.trim() }.filter { it != "badfilter" }
        return if (kept.isEmpty()) line.substring(0, optionsStart) else line.substring(0, optionsStart + 1) + kept.joinToString(",")
    }

    private class OptionState(private val exception: Boolean) {
        var flags = 0
        var positiveTypes = 0
        var negativeTypes = 0
        var pageOptions = 0
        var methodMask = 0
        var domains: DomainConstraint? = null
        var toDomains: DomainConstraint? = null
        var denyAllow: DomainConstraint? = null
        var redirect: String? = null
        var redirectPriority = 0
        var badfilter = false
        var ignoredPageOnly = false
        var count = 0
        /** [NetworkFilter.REMOVEPARAM] / [NetworkFilter.CSP] when this is a modifier filter. */
        var modifierFlag = 0
        var modifierValue = ""

        val matchCase: Boolean get() = flags and NetworkFilter.MATCH_CASE != 0
        val isEmpty: Boolean get() = count == 0

        /** Returns false when the option makes the filter unsupported. */
        fun apply(rawOption: String): Boolean {
            if (rawOption.isEmpty()) return true
            count++
            val eq = rawOption.indexOf('=')
            val rawName = if (eq == -1) rawOption else rawOption.substring(0, eq)
            val value = if (eq == -1) "" else rawOption.substring(eq + 1)
            val negated = rawName.startsWith('~')
            val name = (if (negated) rawName.substring(1) else rawName).lowercase(Locale.ROOT)

            if (name.isEmpty() || name.all { it == '_' }) return true
            if (name in IGNORED_OPTIONS) {
                if (name == "content" || name == "extension") ignoredPageOnly = true
                return true
            }
            if (name in UNSUPPORTED_OPTIONS || name.startsWith("strict-")) return false

            val type = RequestType.fromOption(name)
            if (type != 0) {
                if (negated) negativeTypes = negativeTypes or type else positiveTypes = positiveTypes or type
                if (exception && !negated && type == RequestType.DOCUMENT) pageOptions = pageOptions or PageOption.DOCUMENT
                return true
            }
            when (name) {
                "all" -> positiveTypes = positiveTypes or RequestType.ALL
                "third-party", "3p" -> flags = flags or if (negated) NetworkFilter.FIRST_PARTY else NetworkFilter.THIRD_PARTY
                "first-party", "1p" -> flags = flags or if (negated) NetworkFilter.THIRD_PARTY else NetworkFilter.FIRST_PARTY
                "strict3p" -> flags = flags or NetworkFilter.STRICT_THIRD_PARTY
                "strict1p" -> flags = flags or NetworkFilter.STRICT_FIRST_PARTY
                "important" -> flags = flags or NetworkFilter.IMPORTANT
                "match-case" -> flags = flags or NetworkFilter.MATCH_CASE
                "badfilter" -> badfilter = true
                "domain", "from" -> domains = DomainConstraint.parse(value, '|') ?: return false
                "to" -> toDomains = DomainConstraint.parse(value, '|') ?: return false
                "denyallow" -> denyAllow = DomainConstraint.parse(value, '|') ?: return false
                "method" -> {
                    var include = 0
                    var exclude = 0
                    for (m in value.split('|')) {
                        val neg = m.startsWith('~')
                        val bit = Method.fromName(if (neg) m.substring(1) else m)
                        if (bit == 0) return false
                        if (neg) exclude = exclude or bit else include = include or bit
                    }
                    methodMask = (if (include != 0) include else Method.ALL) and exclude.inv()
                }
                "redirect", "redirect-rule", "rewrite", "empty", "mp4" -> return applyRedirect(name, value)
                "removeparam", "queryprune" -> {
                    if (negated || modifierFlag != 0 || !isValidRemoveParam(value)) return false
                    modifierFlag = NetworkFilter.REMOVEPARAM
                    modifierValue = value
                }
                "csp" -> {
                    if (negated || modifierFlag != 0) return false
                    modifierFlag = NetworkFilter.CSP
                    if (value.isNotEmpty()) modifierValue = metaCspPolicy(value) ?: return false
                }
                "elemhide", "ehide" -> pageOptions = pageOptions or PageOption.ELEMHIDE
                "generichide", "ghide" -> pageOptions = pageOptions or PageOption.GENERICHIDE
                "specifichide", "shide" -> pageOptions = pageOptions or PageOption.SPECIFICHIDE
                "genericblock" -> pageOptions = pageOptions or PageOption.GENERICBLOCK
                "jsinject" -> pageOptions = pageOptions or PageOption.JSINJECT
                "urlblock" -> pageOptions = pageOptions or PageOption.URLBLOCK
                else -> return false
            }
            return true
        }

        /** `name`, `~name`, `/regex/flags` or `~/regex/flags` (empty = every parameter). */
        private fun isValidRemoveParam(value: String): Boolean {
            val spec = value.removePrefix("~")
            if (!spec.startsWith('/')) return value != "~"
            return RemoveParams.compileRegex(spec) != null
        }

        private fun applyRedirect(name: String, value: String): Boolean {
            val (resource, isRule) = when (name) {
                "empty" -> "empty" to false
                "mp4" -> "noopmp4-1s" to false
                "rewrite" -> {
                    if (!value.startsWith("abp-resource:")) return false
                    value.removePrefix("abp-resource:") to false
                }
                else -> value to (name == "redirect-rule")
            }
            val colon = resource.lastIndexOf(':')
            val resourceName = if (colon > 0) resource.substring(0, colon) else resource
            if (colon > 0) redirectPriority = resource.substring(colon + 1).toIntOrNull() ?: return false
            if (exception) {
                // `@@…$redirect-rule` (no value) cancels every redirect; otherwise one resource.
                redirect = resourceName.ifEmpty { "*" }
                flags = flags or NetworkFilter.REDIRECT_RULE
                return true
            }
            // `redirect-rule=none:N` outranks lower-priority redirects so the request is simply blocked.
            if (resourceName == "none" && !isRule) return false
            val canonical = RedirectResources.canonicalName(resourceName) ?: return false
            redirect = canonical
            if (!isRule) flags = flags or NetworkFilter.REDIRECT_BLOCKING else flags = flags or NetworkFilter.REDIRECT_RULE
            return true
        }
    }
}
