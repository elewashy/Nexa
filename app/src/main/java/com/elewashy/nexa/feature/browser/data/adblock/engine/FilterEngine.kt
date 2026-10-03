package com.elewashy.nexa.feature.browser.data.adblock.engine

import java.io.BufferedReader

/** Outcome of evaluating a request against the network filters. */
class MatchResult private constructor(
    val decision: Decision,
    /** Deciding filter text (block, exception or redirect filter); null when nothing matched. */
    val filterText: String?,
    /** Canonical redirect resource for [Decision.REDIRECT]. */
    val redirect: String?,
) {
    enum class Decision { NO_MATCH, ALLOW, BLOCK, REDIRECT }

    val shouldBlock: Boolean get() = decision == Decision.BLOCK || decision == Decision.REDIRECT

    override fun toString(): String = "$decision${filterText?.let { " ($it)" } ?: ""}"

    companion object {
        val NO_MATCH = MatchResult(Decision.NO_MATCH, null, null)
        internal val PAGE_ALLOWLISTED = MatchResult(Decision.ALLOW, "\$document", null)

        internal fun allow(filter: NetworkFilter) = MatchResult(Decision.ALLOW, filter.text, null)
        internal fun block(text: String) = MatchResult(Decision.BLOCK, text, null)
        internal fun redirect(text: String, resource: String) = MatchResult(Decision.REDIRECT, text, resource)
    }
}

/** Page-scoped state computed once per committed document. */
class PageContext internal constructor(
    val host: String,
    internal val hostContext: HostContext,
    /** OR of [PageOption] bits from `@@…$document`, `$elemhide`, `$generichide`, … exceptions. */
    val pageOptions: Int,
) {
    val isAllowlisted: Boolean get() = pageOptions and PageOption.DISABLE_NETWORK != 0
}

/** Filter counts of a compiled engine, shown in Settings. */
data class FilterEngineStats(
    val networkFilters: Int,
    val cosmeticFilters: Int,
    val scriptletFilters: Int,
) {
    val total: Int get() = networkFilters + cosmeticFilters + scriptletFilters
}

/**
 * Compiled, immutable filtering engine. Thread-safe: every structure is
 * read-only after [Builder.build]; the small result caches are synchronized.
 *
 * Decision precedence (AdGuard/uBO compatible, deterministic):
 *  1. Page allowlisted by `@@…$document` / `$urlblock` → allow.
 *  2. `$important` block → blocked unless an `$important` exception matches.
 *  3. Block filter → blocked unless any exception matches.
 *  4. Blocked requests are redirected to a neutered resource when a
 *     `redirect=` / `redirect-rule=` filter (highest priority wins) matches
 *     and no redirect exception cancels it.
 *
 * Modifier filters never block: `$removeparam` filters strip query
 * parameters from page loads ([removeParams]) and `$csp` filters add
 * policies to documents (part of [cosmeticPayload]).
 *
 * An engine can be persisted and restored without recompiling the lists
 * (see [FilterEngineSnapshot]).
 */
class FilterEngine internal constructor(
    internal val resolver: RegistrableDomainResolver,
    internal val pureHostnames: SortedLongSet,
    internal val importantBlocks: NetworkFilterIndex,
    internal val blocks: NetworkFilterIndex,
    internal val exceptions: NetworkFilterIndex,
    internal val redirects: NetworkFilterIndex,
    internal val redirectExceptions: NetworkFilterIndex,
    internal val pageExceptions: NetworkFilterIndex,
    internal val removeParamFilters: NetworkFilterIndex,
    internal val removeParamExceptions: NetworkFilterIndex,
    internal val cspFilters: NetworkFilterIndex,
    internal val cspExceptions: NetworkFilterIndex,
    internal val cosmetics: CosmeticIndex,
    val stats: FilterEngineStats,
) {

    // ── Network ─────────────────────────────────────────────────────────

    /**
     * Builds the [PageContext] for a top-level document URL: the page host
     * plus the page-level exception options that apply to it.
     */
    fun pageContext(pageUrl: String?): PageContext {
        val host = pageUrl?.let(Hostnames::hostOf).orEmpty()
        val hostContext = HostContext.of(host, resolver)
        return PageContext(host, hostContext, if (pageUrl == null) 0 else pageOptions(pageUrl, hostContext))
    }

    private fun pageOptions(url: String, hostContext: HostContext): Int {
        val request = FilterRequest.create(
            url, RequestType.DOCUMENT, null, resolver = resolver, pageContextOverride = hostContext,
        ) ?: return 0
        var options = 0
        pageExceptions.forEachMatch(request, RequestKeys(request)) { options = options or it.pageOptions }
        return options
    }

    /**
     * Evaluates a subresource request issued by [page]. [type] is the
     * request's most likely type; [alternativeTypes] are the other types it
     * may have (see [RequestTypeResolver]) and only widen exceptions.
     */
    fun matchRequest(
        url: String,
        type: Int,
        page: PageContext?,
        method: String = "GET",
        alternativeTypes: Int = 0,
    ): MatchResult {
        if (page != null && page.isAllowlisted) return MatchResult.PAGE_ALLOWLISTED
        val request = FilterRequest.create(
            url, type, page?.host, method, resolver,
            pageContextOverride = page?.hostContext?.takeIf { page.host.isNotEmpty() },
            alternativeTypes = alternativeTypes,
        ) ?: return MatchResult.NO_MATCH
        val genericBlockDisabled = page != null && page.pageOptions and PageOption.GENERICBLOCK != 0
        return evaluate(request, RequestKeys(request), genericBlockDisabled, strictDocument = false)
    }

    /**
     * Evaluates a top-level document load. Besides explicit `$document`
     * filters, pure hostname filters (`||host^`) block documents ("strict
     * blocking", as in uBO) unless a `$document` or pure-hostname exception
     * matches.
     */
    fun matchDocument(url: String, method: String = "GET"): MatchResult {
        val page = pageContext(url)
        if (page.isAllowlisted) return MatchResult.PAGE_ALLOWLISTED
        val request = FilterRequest.create(url, RequestType.DOCUMENT, page.host, method, resolver, page.hostContext)
            ?: return MatchResult.NO_MATCH
        return evaluate(request, RequestKeys(request), page.pageOptions and PageOption.GENERICBLOCK != 0, strictDocument = true)
    }

    /**
     * Whether an exception explicitly allows popups to [url] from [opener]
     * (`@@…$popup`, `@@…$all`), independently of any blocking filter. Lets
     * unbreak lists override popup heuristics as well as filters.
     */
    fun isPopupAllowlisted(url: String, opener: PageContext): Boolean {
        if (opener.isAllowlisted) return true
        val request = FilterRequest.create(url, RequestType.POPUP, opener.host, "GET", resolver, opener.hostContext)
            ?: return false
        return exceptions.find(request, RequestKeys(request)) != null
    }

    /**
     * Whether two http(s) URLs belong to the same site (registrable domain,
     * or identical host for IPs / public suffixes). False when either URL has
     * no network host.
     */
    fun isSameSite(urlA: String, urlB: String): Boolean {
        val hostA = Hostnames.hostOf(urlA) ?: return false
        val hostB = Hostnames.hostOf(urlB) ?: return false
        if (hostA == hostB) return true
        val siteA = resolver.registrableDomain(hostA) ?: return false
        return siteA == resolver.registrableDomain(hostB)
    }

    /**
     * Evaluates a popup (new window, or a page-initiated cross-site
     * navigation) to [url] opened by [opener]. For a navigation the user
     * started by tapping a link ([userGesture]) only target-specific popup
     * filters (hostname-anchored or `to=`) apply, so site-wide `*$popup`
     * filters never swallow ordinary outbound link clicks.
     */
    fun matchPopup(url: String, opener: PageContext, userGesture: Boolean): MatchResult {
        if (opener.isAllowlisted) return MatchResult.PAGE_ALLOWLISTED
        val request = FilterRequest.create(url, RequestType.POPUP, opener.host, "GET", resolver, opener.hostContext)
            ?: return MatchResult.NO_MATCH
        if (!request.isThirdParty) return MatchResult.NO_MATCH
        val keys = RequestKeys(request)
        val targeted: (NetworkFilter) -> Boolean = if (userGesture) {
            { f -> f.flags and NetworkFilter.HOST_ANCHOR != 0 || f.toDomains != null }
        } else {
            { true }
        }
        importantBlocks.find(request, keys, targeted)?.let { block ->
            val exception = exceptions.find(request, keys) { it.isImportant }
            return if (exception != null) MatchResult.allow(exception) else MatchResult.block(block.text)
        }
        val block = blocks.find(request, keys, targeted) ?: return MatchResult.NO_MATCH
        exceptions.find(request, keys)?.let { return MatchResult.allow(it) }
        return MatchResult.block(block.text)
    }

    private fun evaluate(
        request: FilterRequest,
        keys: RequestKeys,
        genericBlockDisabled: Boolean,
        strictDocument: Boolean,
    ): MatchResult {
        val accept: (NetworkFilter) -> Boolean = if (genericBlockDisabled) { f -> !f.isGeneric } else { _ -> true }

        importantBlocks.find(request, keys, accept)?.let { block ->
            val exception = exceptions.find(request, keys) { it.isImportant }
            return if (exception != null) MatchResult.allow(exception) else blocked(request, keys, block.text, block)
        }

        val pureMatch = if (!genericBlockDisabled && (request.type and RequestType.DEFAULT_TYPES != 0 || strictDocument)) {
            pureHostnameMatch(request.host)
        } else {
            null
        }
        val strictHostnameBlock = strictDocument && pureMatch != null
        val blockFilter: NetworkFilter?
        val blockText: String
        if (pureMatch != null) {
            blockFilter = null
            blockText = "||$pureMatch^"
        } else {
            blockFilter = blocks.find(request, keys, accept) ?: return MatchResult.NO_MATCH
            blockText = blockFilter.text
        }

        exceptions.find(request, keys)?.let { return MatchResult.allow(it) }
        if (strictHostnameBlock) {
            // Strict document blocking also yields to pure-hostname exceptions (`@@||host^`),
            // which otherwise only cover subresources.
            val asSubresource = FilterRequest.create(
                request.rawUrl, RequestType.OTHER, request.pageContext.host, "GET", resolver, request.pageContext,
            )
            if (asSubresource != null) {
                exceptions.find(asSubresource, keys) { it.flags and NetworkFilter.PURE_HOSTNAME != 0 }
                    ?.let { return MatchResult.allow(it) }
            }
        }
        return blocked(request, keys, blockText, blockFilter)
    }

    /** Returns the matching hostname suffix when a pure `||host^` filter covers [host]. */
    private fun pureHostnameMatch(host: String): String? {
        if (pureHostnames.size == 0 || host.isEmpty()) return null
        var start = 0
        while (true) {
            if (Fnv.hash(host, start, host.length) in pureHostnames) return host.substring(start)
            val dot = host.indexOf('.', start)
            if (dot == -1) return null
            start = dot + 1
        }
    }

    private fun blocked(request: FilterRequest, keys: RequestKeys, text: String, filter: NetworkFilter?): MatchResult {
        if (request.type and (RequestType.DOCUMENT or RequestType.POPUP) != 0) return MatchResult.block(text)
        var best: NetworkFilter? = filter?.takeIf { it.flags and NetworkFilter.REDIRECT_BLOCKING != 0 }
        redirects.forEachMatch(request, keys) { candidate ->
            val current = best
            if (current == null || candidate.redirectPriority > current.redirectPriority) best = candidate
        }
        val chosen = best ?: return MatchResult.block(text)
        val resource = chosen.redirect ?: return MatchResult.block(text)
        if (resource == RedirectResources.NONE) return MatchResult.block(text)
        val cancelled = redirectExceptions.find(request, keys) { it.redirect == "*" || it.redirect == resource }
        return if (cancelled != null) MatchResult.block(text) else MatchResult.redirect(chosen.text, resource)
    }

    // ── Modifiers ───────────────────────────────────────────────────────

    /**
     * `$removeparam`: the page URL to load instead of [url] once the
     * parameters selected by matching filters are stripped, or null when
     * nothing applies. `@@…$removeparam` cancels every filter,
     * `@@…$removeparam=x` only the identical one.
     */
    fun removeParams(url: String): String? {
        if (removeParamFilters.size == 0 || url.indexOf('?') == -1) return null
        val page = pageContext(url)
        if (page.isAllowlisted) return null
        val request = FilterRequest.create(url, RequestType.DOCUMENT, page.host, "GET", resolver, page.hostContext)
            ?: return null
        val keys = RequestKeys(request)
        val specs = LinkedHashSet<String>()
        removeParamFilters.forEachMatch(request, keys) { specs.add(it.modifier.orEmpty()) }
        if (specs.isEmpty() || !cancelModifiers(removeParamExceptions, request, keys, specs)) return null
        return RemoveParams.strip(url, specs)
    }

    /**
     * `$csp` policies for a document: the top-level page ([topUrl] null or
     * equal) or a frame of [topUrl]. Exceptions cancel all (`@@…$csp`) or
     * identical policies.
     */
    internal fun cspPolicies(frameUrl: String, topUrl: String?): List<String> {
        if (cspFilters.size == 0) return emptyList()
        val pageUrl = topUrl?.takeIf { it != frameUrl }
        val isFrame = pageUrl != null
        val pageHost = Hostnames.hostOf(pageUrl ?: frameUrl).orEmpty()
        val request = FilterRequest.create(
            frameUrl, if (isFrame) RequestType.SUBDOCUMENT else RequestType.DOCUMENT, pageHost, "GET", resolver,
            HostContext.of(pageHost, resolver),
        ) ?: return emptyList()
        val keys = RequestKeys(request)
        val policies = LinkedHashSet<String>()
        cspFilters.forEachMatch(request, keys) { f -> f.modifier?.takeIf { it.isNotEmpty() }?.let(policies::add) }
        if (policies.isEmpty() || !cancelModifiers(cspExceptions, request, keys, policies)) return emptyList()
        return policies.toList()
    }

    /** Removes the values cancelled by matching exceptions; false when an exception cancels them all. */
    private fun cancelModifiers(
        index: NetworkFilterIndex,
        request: FilterRequest,
        keys: RequestKeys,
        values: MutableSet<String>,
    ): Boolean {
        var all = false
        index.forEachMatch(request, keys) { exception ->
            val value = exception.modifier.orEmpty()
            if (value.isEmpty()) all = true else values.remove(value)
        }
        return !all && values.isNotEmpty()
    }

    // ── Cosmetic & scriptlets ───────────────────────────────────────────

    /**
     * Content-script payload (JSON) for a frame: specific + high-generic CSS,
     * procedural filters, whether the low-generic class/id surveyor should
     * run, the scriptlets to execute and the `$csp` policies to apply.
     * [topUrl] propagates top-level `$document` allowlisting and `site>>`
     * filters to frames.
     */
    fun cosmeticPayload(frameUrl: String, topUrl: String?): String {
        val options = effectiveOptions(frameUrl, topUrl)
        val host = Hostnames.hostOf(frameUrl).orEmpty()
        if (host.isEmpty()) return CosmeticIndex.EMPTY_PAYLOAD
        val payload = if (options and (PageOption.DOCUMENT or PageOption.ELEMHIDE) != 0) {
            CosmeticIndex.EMPTY_PAYLOAD
        } else {
            val scriptlets = cosmetics.hasScriptlets && options and SCRIPTLET_DISABLING_OPTIONS == 0
            cosmetics.payload(HostContext.of(host, resolver), topContext(host, topUrl), options, scriptlets)
        }
        val policies = if (options and PageOption.DISABLE_NETWORK != 0) emptyList() else cspPolicies(frameUrl, topUrl)
        if (policies.isEmpty()) return payload
        return buildString(payload.length + 64) {
            append(payload, 0, payload.length - 1)
            append(",\"csp\":[")
            policies.forEachIndexed { i, policy ->
                if (i > 0) append(',')
                append(Json.quote(policy))
            }
            append("]}")
        }
    }

    /** CSS for low-generic selectors keyed by the given `.class` / `#id` tokens. */
    fun genericCss(frameUrl: String, topUrl: String?, tokens: List<String>): String {
        val options = effectiveOptions(frameUrl, topUrl)
        if (options and (PageOption.DOCUMENT or PageOption.ELEMHIDE or PageOption.GENERICHIDE) != 0) return ""
        val host = Hostnames.hostOf(frameUrl).orEmpty()
        return cosmetics.lowGenericCss(HostContext.of(host, resolver), topContext(host, topUrl), tokens)
    }

    /** Context of the top document for a frame on [frameHost]; null for top-level documents. */
    private fun topContext(frameHost: String, topUrl: String?): HostContext? {
        val topHost = topUrl?.let(Hostnames::hostOf)?.takeIf { it.isNotEmpty() && it != frameHost } ?: return null
        return HostContext.of(topHost, resolver)
    }

    /** Scriptlets to inject at document start for documents on [host]. */
    fun scriptletsForHost(host: String): List<ScriptletCall> {
        if (host.isEmpty() || !cosmetics.hasScriptlets) return emptyList()
        val options = pageOptions("https://$host/", HostContext.of(host, resolver))
        if (options and SCRIPTLET_DISABLING_OPTIONS != 0) return emptyList()
        return cosmetics.scriptlets(HostContext.of(host, resolver))
    }

    private fun effectiveOptions(frameUrl: String, topUrl: String?): Int {
        val frameHost = Hostnames.hostOf(frameUrl).orEmpty()
        var options = pageOptions(frameUrl, HostContext.of(frameHost, resolver))
        if (topUrl != null && topUrl != frameUrl) {
            val topHost = Hostnames.hostOf(topUrl).orEmpty()
            options = options or (pageOptions(topUrl, HostContext.of(topHost, resolver)) and PageOption.DOCUMENT)
        }
        return options
    }

    /** Compiles filter lists into an immutable [FilterEngine]. Not thread-safe. */
    class Builder(private val resolver: RegistrableDomainResolver) {
        private val seen = HashSet<String>()
        private val badFilters = HashSet<String>()
        private val pureHostnames = ArrayList<Pair<String, Long>>()
        private val important = NetworkFilterIndex.Builder()
        private val networkFilters = ArrayList<NetworkFilter>()
        private val cosmeticBuilder = CosmeticIndex.Builder()

        /**
         * Parses one list; [trusted] enables trusted-only scriptlets.
         * [onUnsupported] receives every line skipped as unsupported (diagnostics).
         */
        fun addList(
            reader: BufferedReader,
            trusted: Boolean,
            onUnsupported: ((String) -> Unit)? = null,
        ): FilterListStats = FilterListParser(this, trusted, onUnsupported).parse(reader)

        internal fun addHostname(host: String): Boolean {
            val text = "||$host^"
            if (!seen.add(text)) return false
            pureHostnames.add(text to Fnv.hash(host))
            return true
        }

        internal fun addNetwork(filter: NetworkFilter): Boolean {
            if (!seen.add(filter.text)) return false
            if (filter.flags and NetworkFilter.PURE_HOSTNAME != 0 && !filter.isException) {
                pureHostnames.add(filter.text to Fnv.hash(filter.pattern, 0, filter.hostnameLength))
                return true
            }
            networkFilters.add(filter)
            return true
        }

        internal fun addBadFilter(target: String) {
            badFilters.add(target)
        }

        internal fun addCosmetic(filter: CosmeticFilter, line: String): Boolean {
            if (!seen.add(line)) return false
            cosmeticBuilder.add(filter)
            return true
        }

        fun build(): FilterEngine {
            val blocks = NetworkFilterIndex.Builder()
            val exceptions = NetworkFilterIndex.Builder()
            val redirects = NetworkFilterIndex.Builder()
            val redirectExceptions = NetworkFilterIndex.Builder()
            val pageExceptions = NetworkFilterIndex.Builder()
            val removeParams = NetworkFilterIndex.Builder()
            val removeParamExceptions = NetworkFilterIndex.Builder()
            val csps = NetworkFilterIndex.Builder()
            val cspExceptions = NetworkFilterIndex.Builder()
            var networkCount = 0

            for (f in networkFilters) {
                if (f.text in badFilters) continue
                networkCount++
                when {
                    f.flags and NetworkFilter.REMOVEPARAM != 0 -> (if (f.isException) removeParamExceptions else removeParams).add(f)
                    f.flags and NetworkFilter.CSP != 0 -> (if (f.isException) cspExceptions else csps).add(f)
                    f.isException && f.pageOptions != 0 -> pageExceptions.add(f)
                    f.isException && f.flags and NetworkFilter.REDIRECT_RULE != 0 -> redirectExceptions.add(f)
                    f.isException -> exceptions.add(f)
                    f.flags and NetworkFilter.REDIRECT_RULE != 0 -> redirects.add(f)
                    else -> {
                        if (f.isImportant) important.add(f) else blocks.add(f)
                        if (f.flags and NetworkFilter.REDIRECT_BLOCKING != 0) redirects.add(f)
                    }
                }
            }
            val hostHashes = LongArray(pureHostnames.size)
            var n = 0
            for ((text, hash) in pureHostnames) if (text !in badFilters) hostHashes[n++] = hash
            networkCount += n

            val builders = listOf(
                important, blocks, exceptions, redirects, redirectExceptions, pageExceptions,
                removeParams, removeParamExceptions, csps, cspExceptions,
            )
            val frequency = HashMap<Long, Int>()
            val tokens = builders.map { it.collectTokens(frequency) }
            val indexes = builders.mapIndexed { i, b -> b.build(tokens[i], frequency) }
            val cosmeticIndex = cosmeticBuilder.build()

            seen.clear()
            return FilterEngine(
                resolver = resolver,
                pureHostnames = SortedLongSet.of(hostHashes.copyOf(n)),
                importantBlocks = indexes[0],
                blocks = indexes[1],
                exceptions = indexes[2],
                redirects = indexes[3],
                redirectExceptions = indexes[4],
                pageExceptions = indexes[5],
                removeParamFilters = indexes[6],
                removeParamExceptions = indexes[7],
                cspFilters = indexes[8],
                cspExceptions = indexes[9],
                cosmetics = cosmeticIndex,
                stats = FilterEngineStats(
                    networkFilters = networkCount,
                    cosmeticFilters = cosmeticIndex.cosmeticCount,
                    scriptletFilters = cosmeticIndex.scriptletCount,
                ),
            )
        }
    }

    companion object {
        private const val SCRIPTLET_DISABLING_OPTIONS =
            PageOption.DOCUMENT or PageOption.JSINJECT or PageOption.ELEMHIDE or PageOption.SPECIFICHIDE

        /** An engine without filters; every request passes. */
        fun empty(resolver: RegistrableDomainResolver): FilterEngine = Builder(resolver).build()
    }
}
